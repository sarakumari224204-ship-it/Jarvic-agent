package com.jarvis.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jarvis.os.JarvisApp
import com.jarvis.os.R
import com.jarvis.os.domain.agent.AgentState
import com.jarvis.os.domain.agent.InputGuard
import com.jarvis.os.domain.agent.TaskEngine
import com.jarvis.os.presentation.MainActivity
import com.jarvis.os.runtime.JarvisRuntime
import com.jarvis.os.speech.ContinuousSpeechRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class JarvisForegroundService : LifecycleService() {

    private lateinit var speechRecognizer: ContinuousSpeechRecognizer
    private lateinit var taskEngine: TaskEngine
    private lateinit var inputGuard: InputGuard
    private var resumeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as JarvisApp).container
        speechRecognizer = ContinuousSpeechRecognizer(this)
        inputGuard = InputGuard()
        taskEngine = TaskEngine(
            keyStore = container.keyStore,
            gemini = container.geminiClient,
            speech = container.speech,
            conversationStore = container.conversationStore,
            modeRouter = container.modeRouter,
            inputGuard = inputGuard,
            contextAssembler = container.contextAssembler,
            memoryStore = container.memoryStore
        )

        createChannel()
        startForegroundCompat()
        JarvisRuntime.setServiceRunning(true)

        lifecycleScope.launch {
            speechRecognizer.results.collectLatest { text ->
                Log.i(TAG, "SPEECH_RECEIVED source=stt text=$text")
                handleUtterance(text)
            }
        }

        lifecycleScope.launch {
            speechRecognizer.isListening.collectLatest { listening ->
                JarvisRuntime.updateListening(listening)
            }
        }

        lifecycleScope.launch {
            speechRecognizer.partialText.collectLatest { partial ->
                JarvisRuntime.updatePartial(partial)
            }
        }

        lifecycleScope.launch {
            taskEngine.status.collectLatest { status ->
                JarvisRuntime.updateAgent(status.state.name, status.speech ?: status.lastThought)
                updateNotification(statusText(status.state, status.speech ?: status.lastThought))
                when (status.state) {
                    AgentState.SPEAKING, AgentState.THINKING, AgentState.ACTING -> {
                        resumeJob?.cancel()
                        speechRecognizer.pause()
                    }
                    AgentState.WAITING_USER -> {
                        resumeJob?.cancel()
                        speechRecognizer.resume()
                    }
                    AgentState.FINISHED, AgentState.FAILED, AgentState.IDLE -> {
                        scheduleResumeListening()
                    }
                }
            }
        }

        speechRecognizer.start()
        Log.i(TAG, "Service started")
    }

    private fun scheduleResumeListening() {
        resumeJob?.cancel()
        resumeJob = lifecycleScope.launch {
            delay(700)
            if (!taskEngine.isBusy()) {
                speechRecognizer.resume()
            }
        }
    }

    private fun handleUtterance(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val status = taskEngine.status.value
        Log.i(TAG, "handleUtterance state=${status.state} taskId=${status.taskId} text=$trimmed")

        if (status.state == AgentState.WAITING_USER) {
            taskEngine.provideUserReply(trimmed)
            return
        }

        if (taskEngine.isBusy()) {
            Log.w(TAG, "Busy — drop utterance")
            return
        }

        if (!inputGuard.shouldAccept(trimmed)) {
            Log.w(TAG, "InputGuard rejected")
            return
        }

        taskEngine.start(trimmed)
    }

    override fun onDestroy() {
        resumeJob?.cancel()
        speechRecognizer.shutdown()
        taskEngine.shutdown()
        JarvisRuntime.setServiceRunning(false)
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notif_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun statusText(state: AgentState, detail: String?): String = when (state) {
        AgentState.IDLE -> getString(R.string.notif_text)
        AgentState.THINKING -> detail ?: "Thinking…"
        AgentState.ACTING -> "Acting…"
        AgentState.SPEAKING -> detail ?: "Speaking…"
        AgentState.WAITING_USER -> detail ?: "Waiting…"
        AgentState.FINISHED -> detail ?: "Done."
        AgentState.FAILED -> detail ?: "Failed."
    }

    companion object {
        private const val TAG = "JarvisFgService"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            context.startForegroundService(Intent(context, JarvisForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisForegroundService::class.java))
        }
    }
}
