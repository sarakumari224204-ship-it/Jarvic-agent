package com.jarvis.os.domain.agent

import android.util.Log
import com.jarvis.os.accessibility.ActionResult
import com.jarvis.os.accessibility.JarvisAccessibilityService
import com.jarvis.os.data.remote.ApiResult
import com.jarvis.os.data.remote.GeminiClient
import com.jarvis.os.data.secure.ConversationStore
import com.jarvis.os.data.secure.SecureKeyStore
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_STEPS = 25
private const val GEMINI_TIMEOUT_MS = 40_000L

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
    val speech: String? = null
)

class TaskEngine(
    private val keyStore: SecureKeyStore,
    private val gemini: GeminiClient,
    private val speech: SpeechSynthesizer,
    private val conversationStore: ConversationStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val userReplies = mutableListOf<String>()

    fun start(objective: String) {
        job?.cancel()
        userReplies.clear()
        job = scope.launch {
            runLoop(objective.trim())
        }
    }

    fun provideUserReply(text: String) {
        if (_status.value.state == AgentState.WAITING_USER) {
            userReplies += text.trim()
            Log.i(TAG, "USER_REPLY: $text")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = AgentStatus(state = AgentState.IDLE)
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun runLoop(objective: String) {
        Log.i(TAG, "TASK_STARTED objective=$objective")

        val settings = keyStore.current()
        val apiKey = settings.geminiKey
        if (apiKey.isNullOrBlank()) {
            speakAndFinish("Gemini API key missing hai. Settings mein daalo.", failed = true)
            return
        }

        val model = SecureKeyStore.migrateModel(settings.geminiModel.ifBlank { ApiDefaults.GEMINI_MODEL })
        Log.i(TAG, "TASK_PLANNING model=$model")

        val service = JarvisAccessibilityService.instance.value
        if (service == null) {
            speakAndFinish("Accessibility service on nahi hai. Settings se enable karo.", failed = true)
            return
        }

        JarvisAccessibilityService.blockedPackages = setOf(
            service.packageName,
            "com.android.systemui"
        )

        // Immediate acknowledgement – never leave user in silence
        _status.value = AgentStatus(
            state = AgentState.SPEAKING,
            objective = objective,
            step = 0,
            maxSteps = MAX_STEPS,
            speech = "Okay, kar rahi hoon.",
            lastThought = "Acknowledged"
        )
        try {
            speech.speak("Okay, kar rahi hoon.")
        } catch (e: Exception) {
            Log.w(TAG, "ack speak failed: ${e.message}")
        }

        val history = mutableListOf<String>()
        var step = 0

        _status.value = AgentStatus(
            state = AgentState.THINKING,
            objective = objective,
            step = 0,
            maxSteps = MAX_STEPS,
            lastThought = "Using $model"
        )

        conversationStore.append(ChatMessage(ChatRole.USER, objective))

        try {
            while (scope.isActive && step < MAX_STEPS) {
                step++
                _status.value = _status.value.copy(
                    state = AgentState.THINKING,
                    step = step,
                    lastThought = "Planning step $step…"
                )
                Log.i(TAG, "TASK_PLANNING step=$step/$MAX_STEPS")

                val snapshot = service.inspectScreen()
                val screenText = snapshot?.toPrompt() ?: "[NO SCREEN] Unable to read the current screen."

                val conversation = conversationStore.recent(6)
                val userPrompt = AgentPrompts.userTurn(
                    objective = objective,
                    step = step,
                    maxSteps = MAX_STEPS,
                    history = history,
                    conversation = conversation,
                    userReplies = userReplies.toList(),
                    screen = screenText
                )

                var raw = callGemini(apiKey, model, userPrompt)
                var parsed = AgentDecisionParser.parse(raw)

                if (parsed.isFailure) {
                    Log.w(TAG, "parse fail, repair attempt: ${parsed.exceptionOrNull()?.message}")
                    val repair = AgentPrompts.repairTurn(parsed.exceptionOrNull()?.message ?: "parse error")
                    raw = callGemini(apiKey, model, "$userPrompt\n\n$repair")
                    parsed = AgentDecisionParser.parse(raw)
                }

                if (parsed.isFailure) {
                    history += "step $step: INVALID_REPLY (${parsed.exceptionOrNull()?.message})"
                    Log.w(TAG, "INVALID_REPLY step=$step")
                    continue
                }

                val stepResult = parsed.getOrThrow()
                Log.i(TAG, "ACTION_SELECTED step=$step action=${stepResult.action.describe()} thought=${stepResult.thought}")

                _status.value = _status.value.copy(
                    lastThought = stepResult.thought,
                    state = AgentState.ACTING
                )

                val actionResult = execute(service, stepResult.action)
                Log.i(TAG, "ACTION_EXECUTED success=${actionResult.success} msg=${actionResult.message}")
                history += "step $step: ${stepResult.action.describe()} → ${actionResult.message}"

                _status.value = _status.value.copy(lastResult = actionResult.message)

                // Light verification: after app launch / click, wait for UI settle
                if (actionResult.success) {
                    when (stepResult.action) {
                        is AgentAction.OpenApp,
                        is AgentAction.Click,
                        is AgentAction.Tap,
                        is AgentAction.Global -> {
                            service.awaitUiSettled()
                            Log.i(TAG, "ACTION_VERIFIED pkg=${service.currentPackage()}")
                        }
                        else -> Unit
                    }
                }

                when (val action = stepResult.action) {
                    is AgentAction.Finish -> {
                        Log.i(TAG, "TASK_COMPLETED")
                        speakAndFinish(action.speech.ifBlank { "Ho gaya." })
                        return
                    }
                    is AgentAction.Fail -> {
                        Log.i(TAG, "TASK_FAILED: ${action.speech}")
                        speakAndFinish(
                            action.speech.ifBlank { "Ye kaam nahi ho paya." },
                            failed = true
                        )
                        return
                    }
                    is AgentAction.AskUser -> {
                        _status.value = _status.value.copy(
                            state = AgentState.WAITING_USER,
                            speech = action.speech
                        )
                        speech.speak(action.speech)
                        val before = userReplies.size
                        var waited = 0
                        while (userReplies.size == before && waited < 60_000 && scope.isActive) {
                            delay(300)
                            waited += 300
                        }
                        if (userReplies.size == before) {
                            speakAndFinish("Reply nahi mila. Rok rahi hoon.", failed = true)
                            return
                        }
                    }
                    AgentAction.Wait -> {
                        delay(800)
                        service.awaitUiSettled()
                    }
                    else -> {
                        if (!actionResult.success) {
                            // Stay in loop – model can try another approach
                            Log.w(TAG, "action failed, continuing loop")
                        }
                        service.awaitUiSettled()
                    }
                }
            }

            Log.w(TAG, "TASK_FAILED step limit")
            speakAndFinish("Zyada steps lag gaye. Simple command try karo.", failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop crashed", e)
            speakAndFinish("Kuch gadbad ho gayi: ${e.message ?: "unknown"}", failed = true)
        }
    }

    private suspend fun callGemini(apiKey: String, model: String, userText: String): String {
        val timed = withTimeoutOrNull(GEMINI_TIMEOUT_MS) {
            gemini.generateJson(apiKey, model, AgentPrompts.SYSTEM, userText)
        }

        if (timed == null) {
            Log.w(TAG, "Gemini TIMEOUT model=$model after ${GEMINI_TIMEOUT_MS}ms")
            return "{\"thought\":\"timeout\",\"action\":\"fail\",\"speech\":\"Gemini reply late aa raha hai. Thodi der baad try karo.\"}"
        }

        return when (timed) {
            is ApiResult.Success -> {
                Log.d(TAG, "Gemini OK chars=${timed.value.length}")
                timed.value
            }
            is ApiResult.Failure -> {
                Log.w(TAG, "Gemini FAIL model=$model err=${timed.error.message}")
                val msg = timed.error.userMessage().replace("\"", "'")
                "{\"thought\":\"API error\",\"action\":\"fail\",\"speech\":\"$msg\"}"
            }
        }
    }

    private suspend fun execute(service: JarvisAccessibilityService, action: AgentAction): ActionResult {
        return withContext(Dispatchers.Default) {
            when (action) {
                is AgentAction.OpenApp -> service.launchApp(action.app)
                is AgentAction.Click -> service.clickNode(action.nodeId)
                is AgentAction.LongClick -> service.longClickNode(action.nodeId)
                is AgentAction.TypeText -> service.setText(action.text, action.nodeId, action.append)
                is AgentAction.PressEnter -> service.pressEnter(action.nodeId)
                is AgentAction.Scroll -> service.scroll(action.direction)
                is AgentAction.Tap -> service.tap(action.x, action.y)
                is AgentAction.Swipe -> service.swipe(action.x, action.y, action.x2, action.y2)
                is AgentAction.Global -> service.globalAction(action.command)
                is AgentAction.Dial -> service.dial(action.number, action.placeCall)
                AgentAction.Wait -> ActionResult.ok("Waited")
                is AgentAction.AskUser -> ActionResult.ok("Asked user")
                is AgentAction.Finish -> ActionResult.ok("Finished")
                is AgentAction.Fail -> ActionResult.fail(action.speech)
            }
        }
    }

    private suspend fun speakAndFinish(text: String, failed: Boolean = false) {
        _status.value = _status.value.copy(
            state = AgentState.SPEAKING,
            speech = text
        )
        if (text.isNotBlank()) {
            try {
                speech.speak(text)
            } catch (e: Exception) {
                Log.w(TAG, "speak failed: ${e.message}")
            }
            conversationStore.append(ChatMessage(ChatRole.ASSISTANT, text))
        }
        _status.value = _status.value.copy(
            state = if (failed) AgentState.FAILED else AgentState.FINISHED,
            speech = text
        )
        Log.i(TAG, if (failed) "TASK_FAILED done" else "TASK_COMPLETED done")
        // Return to IDLE so next speech can start a new task
        delay(400)
        if (_status.value.state == AgentState.FINISHED || _status.value.state == AgentState.FAILED) {
            _status.value = _status.value.copy(state = AgentState.IDLE)
        }
    }

    companion object {
        private const val TAG = "TaskEngine"
    }
}
