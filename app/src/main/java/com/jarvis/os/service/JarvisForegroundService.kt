package com.jarvis.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jarvis.os.JarvisApp
import com.jarvis.os.R
import com.jarvis.os.domain.agent.AgentState
import com.jarvis.os.domain.agent.TaskEngine
import com.jarvis.os.presentation.MainActivity
import com.jarvis.os.speech.ContinuousSpeechRecognizer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class JarvisForegroundService : LifecycleService() {

    private lateinit var speechRecognizer: ContinuousSpeechRecognizer
    private lateinit var taskEngine: TaskEngine

    override fun onCreate() {
        super.onCreate()
        val container = (application as JarvisApp).container
        speechRecognizer = ContinuousSpeechRecognizer(this)
        taskEngine = TaskEngine(
            keyStore = container.keyStore,
            gemini = container.geminiClient,
            speech = container.speech,
            conversationStore = container.conversationStore
        )

        createChannel()
        startForegroundCompat()

        // Forward final transcripts to the agent
        lifecycleScope.launch {
            speechRecognizer.results.collectLatest { text ->
                handleUtterance(text)
            }
        }

        // Keep notification text in sync with agent state
        lifecycleScope.launch {
            taskEngine.status.collectLatest { status ->
                updateNotification(statusText(status.state, status.speech ?: status.lastThought))
            }
        }

        speechRecognizer.start()
    }

    private fun handleUtterance(text: String) {
        val status = taskEngine.status.value
        when (status.state) {
            AgentState.WAITING_USER -> taskEngine.provideUserReply(text)
            AgentState.IDLE, AgentState.FINISHED, AgentState.FAILED -> {
                if (text.isNotBlank()) taskEngine.start(text)
            }
            else -> {
                // Agent is busy; ignore or could queue
            }
        }
    }

    override fun onDestroy() {
        speechRecognizer.shutdown()
        taskEngine.shutdown()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notif_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
        AgentState.THINKING -> "Thinking…"
        AgentState.ACTING -> "Acting on screen…"
        AgentState.SPEAKING -> detail ?: "Speaking…"
        AgentState.WAITING_USER -> detail ?: "Waiting for your reply…"
        AgentState.FINISHED -> detail ?: "Done."
        AgentState.FAILED -> detail ?: "Failed."
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisForegroundService::class.java))
        }
    }
}
