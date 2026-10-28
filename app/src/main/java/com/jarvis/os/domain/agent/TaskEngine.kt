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

private const val MAX_STEPS = 12
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
    val speech: String? = null
)

class TaskEngine(
    private val keyStore: SecureKeyStore,
    private val gemini: GeminiClient,
    private val speech: SpeechSynthesizer,
    private val conversationStore: ConversationStore,
    private val modeRouter: ModeRouter? = null
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
            val finished = withTimeoutOrNull(OVERALL_TASK_TIMEOUT_MS) {
                runLoop(objective.trim())
            }
            if (finished == null) {
                Log.e(TAG, "TASK_FAILED overall timeout")
                speakAndFinish("Time out ho gaya. Dobara try karo.", failed = true)
            }
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

        val mode = keyStore.aiMode.first()
        val settings = keyStore.current()

        // Immediate acknowledgement — never silent
        _status.value = AgentStatus(
            state = AgentState.SPEAKING,
            objective = objective,
            speech = "Okay, kar rahi hoon.",
            lastThought = "ack"
        )
        try {
            speech.speak("Okay, kar rahi hoon.")
        } catch (e: Exception) {
            Log.w(TAG, "ack speak failed: ${e.message}")
        }

        val service = JarvisAccessibilityService.instance.value
        if (service != null) {
            JarvisAccessibilityService.blockedPackages = setOf(
                service.packageName,
                "com.android.systemui"
            )
        }

        // ── Local deterministic commands (no API) ──────────────────────────
        if (service != null) {
            val local = tryLocalCommand(service, objective)
            if (local != null) {
                Log.i(TAG, "LOCAL_COMMAND handled")
                speakAndFinish(local.text, failed = !local.ok)
                return
            }
        }

        // Offline / no key → conversation path
        val preferOffline = mode == AIMode.OFFLINE_ONLY ||
            (mode == AIMode.AUTO && settings.geminiKey.isNullOrBlank())

        if (preferOffline && modeRouter != null) {
            runOfflineConversation(objective)
            return
        }

        val apiKey = settings.geminiKey
        if (apiKey.isNullOrBlank()) {
            if (modeRouter != null) {
                runOfflineConversation(objective)
                return
            }
            speakAndFinish(
                "Gemini API key missing. Settings mein daalo, ya Offline model download karo.",
                failed = true
            )
            return
        }

        if (service == null) {
            if (modeRouter != null) {
                runOfflineConversation(objective)
                return
            }
            speakAndFinish("Accessibility service on nahi hai. Settings se enable karo.", failed = true)
            return
        }

        val model = SecureKeyStore.migrateModel(settings.geminiModel.ifBlank { ApiDefaults.GEMINI_MODEL })
        Log.i(TAG, "TASK_PLANNING model=$model mode=$mode")

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
                    lastThought = "Planning $step…"
                )
                Log.i(TAG, "TASK_PLANNING step=$step/$MAX_STEPS")

                val snapshot = service.inspectScreen()
                val screenText = snapshot?.toPrompt() ?: "[NO SCREEN]"
                val conversation = conversationStore.recent(4)
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
                    val repair = AgentPrompts.repairTurn(parsed.exceptionOrNull()?.message ?: "parse error")
                    raw = callGemini(apiKey, model, "$userPrompt\n$repair")
                    parsed = AgentDecisionParser.parse(raw)
                }

                if (parsed.isFailure) {
                    Log.w(TAG, "PARSE_FAIL step=$step")
                    history += "step $step: INVALID_REPLY"
                    continue
                }

                val stepResult = parsed.getOrThrow()
                Log.i(TAG, "ACTION_SELECTED ${stepResult.action.describe()}")

                _status.value = _status.value.copy(
                    lastThought = stepResult.thought,
                    state = AgentState.ACTING
                )

                val actionResult = execute(service, stepResult.action)
                Log.i(TAG, "ACTION_EXECUTED success=${actionResult.success} ${actionResult.message}")
                history += "step $step: ${stepResult.action.describe()} → ${actionResult.message}"
                _status.value = _status.value.copy(lastResult = actionResult.message)

                if (actionResult.success) {
                    when (stepResult.action) {
                        is AgentAction.OpenApp,
                        is AgentAction.Click,
                        is AgentAction.Tap,
                        is AgentAction.Global -> service.awaitUiSettled()
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
                        Log.i(TAG, "TASK_FAILED speech=${action.speech}")
                        speakAndFinish(action.speech.ifBlank { "Ye kaam nahi ho paya." }, failed = true)
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
                        while (userReplies.size == before && waited < 30_000 && scope.isActive) {
                            delay(300)
                            waited += 300
                        }
                        if (userReplies.size == before) {
                            speakAndFinish("Reply nahi mila.", failed = true)
                            return
                        }
                    }
                    AgentAction.Wait -> {
                        delay(400)
                        service.awaitUiSettled()
                    }
                    else -> service.awaitUiSettled()
                }
            }

            Log.i(TAG, "TASK_FAILED steps limit")
            speakAndFinish("Steps limit. Simple command try karo jaise Chrome kholo.", failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "TASK_FAILED exception", e)
            speakAndFinish("Error: ${e.message ?: "unknown"}", failed = true)
        }
    }

    private data class LocalResult(val ok: Boolean, val text: String)

    /** Deterministic phone commands without Gemini. */
    private suspend fun tryLocalCommand(service: JarvisAccessibilityService, objective: String): LocalResult? {
        val t = objective.trim().lowercase()

        // Home
        if (t.matches(Regex(".*\\b(home|homescreen|ghar|होम)\\b.*")) &&
            (t.contains("jao") || t.contains("jaao") || t.contains("go") || t.contains("khol") || t.length < 12)
        ) {
            Log.i(TAG, "LOCAL home")
            val r = service.globalAction(GlobalCommand.HOME)
            return LocalResult(r.success, if (r.success) "Home screen par aa gaye." else r.message)
        }

        // Back
        if (t.matches(Regex(".*\\b(back|peeche|पीछे|वापस)\\b.*")) && t.length < 24) {
            Log.i(TAG, "LOCAL back")
            val r = service.globalAction(GlobalCommand.BACK)
            return LocalResult(r.success, if (r.success) "Back kar diya." else r.message)
        }

        // Open app
        val appTarget = extractOpenAppTarget(objective)
        if (appTarget != null) {
            Log.i(TAG, "LOCAL_FAST_PATH open_app=$appTarget")
            _status.value = _status.value.copy(state = AgentState.ACTING, lastThought = "Opening $appTarget")
            val result = service.launchApp(appTarget)
            Log.i(TAG, "ACTION_VERIFIED success=${result.success} msg=${result.message}")
            return LocalResult(
                result.success,
                if (result.success) "$appTarget khol diya." else result.message.ifBlank { "$appTarget nahi khul paya." }
            )
        }

        // Settings → WiFi style
        if ((t.contains("wifi") || t.contains("wi-fi") || t.contains("वाईफाई")) &&
            (t.contains("setting") || t.contains("khol") || t.contains("open"))
        ) {
            Log.i(TAG, "LOCAL wifi settings")
            _status.value = _status.value.copy(state = AgentState.ACTING, lastThought = "WiFi settings")
            val open = service.launchApp("Settings")
            if (!open.success) return LocalResult(false, open.message)
            service.awaitUiSettled(500, 3000)
            val snap = service.inspectScreen()
            val wifiNode = snap?.nodes?.firstOrNull { n ->
                val blob = listOfNotNull(n.text, n.contentDescription, n.viewId).joinToString(" ").lowercase()
                blob.contains("wifi") || blob.contains("wi-fi") || blob.contains("network") || blob.contains("internet")
            }
            if (wifiNode != null) {
                val click = service.clickNode(wifiNode.id)
                service.awaitUiSettled()
                Log.i(TAG, "ACTION_VERIFIED wifi click success=${click.success}")
                return LocalResult(
                    click.success,
                    if (click.success) "WiFi settings khol diye." else "Settings khule, WiFi option nahi mila."
                )
            }
            return LocalResult(true, "Settings khol diye. WiFi option screen par dikh nahi raha.")
        }

        return null
    }

    private suspend fun runOfflineConversation(objective: String) {
        val router = modeRouter
        if (router == null) {
            speakAndFinish("Offline AI available nahi hai.", failed = true)
            return
        }

        _status.value = _status.value.copy(state = AgentState.THINKING, lastThought = "Offline AI…")
        conversationStore.append(ChatMessage(ChatRole.USER, objective))

        val system = """You are SARA, a helpful voice assistant on Android.
Reply in short spoken Hindi or Hinglish (1-3 sentences).
Do not use markdown. Do not claim you performed device actions unless confirmed.""".trim()

        val result = withTimeoutOrNull(OFFLINE_TIMEOUT_MS) {
            router.generate(system, objective, maxTokens = 160)
        }

        when (result) {
            null -> {
                Log.e(TAG, "TASK_FAILED offline timeout")
                speakAndFinish("Offline model slow hai ya load nahi hua.", failed = true)
            }
            is AIEngineResult.Success -> {
                Log.i(TAG, "TASK_COMPLETED offline")
                speakAndFinish(result.text)
            }
            is AIEngineResult.Failure -> {
                Log.e(TAG, "TASK_FAILED offline ${result.kind} ${result.message}")
                val msg = when (result.kind) {
                    AIEngineResult.FailureKind.MODEL_NOT_INSTALLED ->
                        "Offline AI model isn't downloaded yet. Settings se download karo."
                    AIEngineResult.FailureKind.INSUFFICIENT_MEMORY -> result.message
                    else -> result.message
                }
                speakAndFinish(msg, failed = true)
            }
        }
    }

    private fun extractOpenAppTarget(objective: String): String? {
        val t = objective.trim().lowercase()
        val patterns = listOf(
            Regex("""(?:open|launch|start)\s+(.+)"""),
            Regex("""(.+?)\s*(?:kholo|khol\s*do|khol\s*dena|khol\s*diya|open\s*karo|start\s*karo)"""),
            Regex("""(.+?)\s*(?:खोलो|खोल\s*दो)""")
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            val name = m.groupValues[1].trim()
                .removePrefix("the ")
                .removeSuffix(" app")
                .removeSuffix(" application")
                .trim()
            if (name.length in 2..40 &&
                !name.contains("settings") &&
                !name.contains("wifi") &&
                !name.contains("setting")
            ) {
                return name
            }
        }
        // Bare app names: "chrome", "youtube"
        val known = listOf(
            "chrome", "youtube", "whatsapp", "gmail", "maps", "instagram",
            "facebook", "twitter", "telegram", "snapchat", "spotify", "netflix",
            "camera", "gallery", "photos", "phone", "contacts", "messages", "calculator"
        )
        val bare = t.trim()
        if (bare in known) return bare
        return null
    }

    private suspend fun callGemini(apiKey: String, model: String, userText: String): String {
        val timed = withTimeoutOrNull(GEMINI_TIMEOUT_MS) {
            gemini.generateJson(apiKey, model, AgentPrompts.SYSTEM, userText)
        }
        if (timed == null) {
            Log.w(TAG, "Gemini TIMEOUT model=$model")
            return "{\"thought\":\"timeout\",\"action\":\"fail\",\"speech\":\"Gemini slow hai. Model change karke try karo.\"}"
        }
        return when (timed) {
            is ApiResult.Success -> timed.value
            is ApiResult.Failure -> {
                Log.w(TAG, "Gemini FAIL: ${timed.error.message}")
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
        _status.value = _status.value.copy(state = AgentState.SPEAKING, speech = text)
        if (text.isNotBlank()) {
            try {
                speech.speak(text)
            } catch (e: Exception) {
                Log.w(TAG, "final speak failed: ${e.message}")
            }
            conversationStore.append(ChatMessage(ChatRole.ASSISTANT, text))
        }
        _status.value = _status.value.copy(
            state = if (failed) AgentState.FAILED else AgentState.FINISHED,
            speech = text
        )
        // Brief hold so service can pause STT during TTS, then return to IDLE/listening
        delay(400)
        _status.value = _status.value.copy(state = AgentState.IDLE)
        Log.i(TAG, if (failed) "TASK_FAILED done" else "TASK_COMPLETED done")
    }

    companion object {
        private const val TAG = "TaskEngine"
    }
}
