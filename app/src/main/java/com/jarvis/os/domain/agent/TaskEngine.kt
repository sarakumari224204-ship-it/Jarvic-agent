package com.jarvis.os.domain.agent

import android.util.Log
import com.jarvis.os.accessibility.ActionResult
import com.jarvis.os.accessibility.GlobalCommand
import com.jarvis.os.accessibility.JarvisAccessibilityService
import com.jarvis.os.data.remote.ApiResult
import com.jarvis.os.data.remote.GeminiClient
import com.jarvis.os.data.secure.ConversationStore
import com.jarvis.os.data.secure.SecureKeyStore
import com.jarvis.os.domain.ai.AIEngineResult
import com.jarvis.os.domain.ai.AIMode
import com.jarvis.os.domain.ai.ModeRouter
import com.jarvis.os.domain.memory.ContextAssembler
import com.jarvis.os.domain.model.AgentAction
import com.jarvis.os.domain.model.AgentDecisionParser
import com.jarvis.os.domain.model.ApiDefaults
import com.jarvis.os.domain.model.ChatMessage
import com.jarvis.os.domain.model.ChatRole
import com.jarvis.os.domain.model.describe
import com.jarvis.os.domain.speech.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val MAX_STEPS = 10
private const val GEMINI_TIMEOUT_MS = 18_000L
private const val OFFLINE_TIMEOUT_MS = 45_000L
private const val OVERALL_TASK_TIMEOUT_MS = 40_000L

enum class AgentState {
    IDLE, THINKING, ACTING, SPEAKING, WAITING_USER, FINISHED, FAILED
}

data class AgentStatus(
    val state: AgentState = AgentState.IDLE,
    val objective: String? = null,
    val step: Int = 0,
    val maxSteps: Int = MAX_STEPS,
    val lastThought: String? = null,
    val lastResult: String? = null,
    val speech: String? = null,
    val taskId: Long = 0L
)

class TaskEngine(
    private val keyStore: SecureKeyStore,
    private val gemini: GeminiClient,
    private val speech: SpeechSynthesizer,
    private val conversationStore: ConversationStore,
    private val modeRouter: ModeRouter? = null,
    private val inputGuard: InputGuard = InputGuard(),
    private val contextAssembler: ContextAssembler? = null,
    private val memoryStore: com.jarvis.os.data.memory.MemoryStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val taskSeq = AtomicLong(0)
    private val busy = AtomicBoolean(false)

    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val userReplies = mutableListOf<String>()

    fun inputGuard(): InputGuard = inputGuard

    fun isBusy(): Boolean = busy.get() || when (_status.value.state) {
        AgentState.THINKING, AgentState.ACTING, AgentState.SPEAKING, AgentState.WAITING_USER -> true
        else -> false
    }

    fun start(objective: String): Long? {
        val text = objective.trim()
        if (text.isEmpty()) return null
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "TASK_REJECTED concurrent objective=$text")
            return null
        }
        job?.cancel()
        userReplies.clear()
        val id = taskSeq.incrementAndGet()
        Log.i(TAG, "TASK_QUEUED id=$id objective=$text")
        job = scope.launch {
            try {
                val finished = withTimeoutOrNull(OVERALL_TASK_TIMEOUT_MS) { runLoop(id, text) }
                if (finished == null) {
                    Log.e(TAG, "TASK_FAILED id=$id overall timeout")
                    speakAndFinish(id, "Time out ho gaya. Dobara try karo.", failed = true)
                }
            } finally {
                busy.set(false)
                Log.i(TAG, "TASK_UNLOCK id=$id")
            }
        }
        return id
    }

    // TEMPORARY_PARTIAL - full content follows in next update if this succeeds as test
    companion object {
        private const val TAG = "TaskEngine"
    }
}
