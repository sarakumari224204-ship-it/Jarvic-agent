package com.jarvis.os.domain.agent

import android.util.Log
import com.jarvis.os.accessibility.ActionResult
import com.jarvis.os.accessibility.GlobalCommand
import com.jarvis.os.accessibility.JarvisAccessibilityService
import com.jarvis.os.accessibility.ScreenSnapshot
import com.jarvis.os.accessibility.ScrollDirection
import com.jarvis.os.data.remote.ApiResult
import com.jarvis.os.data.remote.GeminiClient
import com.jarvis.os.data.secure.ConversationStore
import com.jarvis.os.data.secure.SecureKeyStore
import com.jarvis.os.domain.model.AgentAction
import com.jarvis.os.domain.model.AgentDecisionParser
import com.jarvis.os.domain.model.AgentStep
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
            // Resume is handled by the waiting loop checking the list size
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
        val settings = keyStore.current()
        val apiKey = settings.geminiKey
        if (apiKey.isNullOrBlank()) {
            speakAndFinish("Gemini API key is missing. Please add it in settings.", failed = true)
            return
        }

        val service = JarvisAccessibilityService.instance.value
        if (service == null) {
            speakAndFinish("Accessibility service is not enabled. Please turn it on in Settings.", failed = true)
            return
        }

        // Block our own package + system UI from being operated
        JarvisAccessibilityService.blockedPackages = setOf(
            service.packageName,
            "com.android.systemui"
        )

        val history = mutableListOf<String>()
        var step = 0

        _status.value = AgentStatus(
            state = AgentState.THINKING,
            objective = objective,
            step = 0,
            maxSteps = MAX_STEPS
        )

        conversationStore.append(ChatMessage(ChatRole.USER, objective))

        try {
            while (scope.isActive && step < MAX_STEPS) {
                step++
                _status.value = _status.value.copy(state = AgentState.THINKING, step = step)

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

                var raw = callGemini(apiKey, settings.geminiModel, userPrompt)
                var parsed = AgentDecisionParser.parse(raw)

                // One repair attempt
                if (parsed.isFailure) {
                    val repair = AgentPrompts.repairTurn(parsed.exceptionOrNull()?.message ?: "parse error")
                    raw = callGemini(apiKey, settings.geminiModel, "$userPrompt\n\n$repair")
                    parsed = AgentDecisionParser.parse(raw)
                }

                val stepResult = parsed.getOrElse {
                    history += "step $step: INVALID_REPLY (${it.message})"
                    continue
                }

                _status.value = _status.value.copy(
                    lastThought = stepResult.thought,
                    state = AgentState.ACTING
                )

                val actionResult = execute(service, stepResult.action)
                history += "step $step: ${stepResult.action.describe()} → ${actionResult.message}"

                _status.value = _status.value.copy(lastResult = actionResult.message)

                when (val action = stepResult.action) {
                    is AgentAction.Finish -> {
                        speakAndFinish(action.speech.ifBlank { "Done." })
                        return
                    }
                    is AgentAction.Fail -> {
                        speakAndFinish(action.speech.ifBlank { "I could not complete the task." }, failed = true)
                        return
                    }
                    is AgentAction.AskUser -> {
                        _status.value = _status.value.copy(
                            state = AgentState.WAITING_USER,
                            speech = action.speech
                        )
                        speech.speak(action.speech)
                        val before = userReplies.size
                        // Wait for user reply (max 60s)
                        var waited = 0
                        while (userReplies.size == before && waited < 60_000 && scope.isActive) {
                            delay(300)
                            waited += 300
                        }
                        if (userReplies.size == before) {
                            speakAndFinish("I did not get a reply. Stopping.", failed = true)
                            return
                        }
                    }
                    AgentAction.Wait -> {
                        delay(800)
                        service.awaitUiSettled()
                    }
                    else -> {
                        // Give UI time to settle after gesture / app launch
                        service.awaitUiSettled()
                    }
                }
            }

            speakAndFinish("I reached the step limit without finishing. Please try a simpler request.", failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop crashed", e)
            speakAndFinish("Something went wrong: ${e.message ?: "unknown error"}", failed = true)
        }
    }

    private suspend fun callGemini(apiKey: String, model: String, userText: String): String {
        return when (val result = gemini.generateJson(apiKey, model, AgentPrompts.SYSTEM, userText)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                Log.w(TAG, "Gemini error: ${result.error.message}")
                "{\"thought\":\"API error\",\"action\":\"fail\",\"speech\":\"${result.error.userMessage()}\"}"
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
            state = if (failed) AgentState.FAILED else AgentState.FINISHED,
            speech = text
        )
        if (text.isNotBlank()) {
            _status.value = _status.value.copy(state = AgentState.SPEAKING)
            speech.speak(text)
            conversationStore.append(ChatMessage(ChatRole.ASSISTANT, text))
        }
        _status.value = _status.value.copy(
            state = if (failed) AgentState.FAILED else AgentState.FINISHED
        )
    }

    companion object {
        private const val TAG = "TaskEngine"
        const val MAX_STEPS = 25
    }
}
