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
                handleUtterance(text)
            }
        }

        lifecycleScope.launch {
            taskEngine.status.collectLatest { status ->
                when (status.state) {
                    AgentState.SPEAKING, AgentState.THINKING, AgentState.ACTING, AgentState.WAITING_USER -> {
                        speechRecognizer.pause()
                    }
                    AgentState.FINISHED, AgentState.FAILED, AgentState.IDLE -> {
                        scheduleResume()
                    }
                }
                updateNotification(status.speech ?: status.lastThought ?: "Listening…")
            }
        }

        speechRecognizer.start()
        Log.i(TAG, "Service started")
    }

    private fun handleUtterance(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        Log.i(TAG, "UTTERANCE: $cleaned")
        if (!inputGuard.allow(cleaned)) {
            Log.w(TAG, "InputGuard blocked")
            return
        }
        if (taskEngine.isBusy()) {
            if (taskEngine.status.value.state == AgentState.WAITING_USER) {
                taskEngine.provideUserReply(cleaned)
            } else {
                Log.w(TAG, "Busy — drop utterance")
            }
            return
        }
        taskEngine.start(cleaned)
    }

    private fun scheduleResume() {
        resumeJob?.cancel()
        resumeJob = lifecycleScope.launch {
            delay(600)
            if (!taskEngine.isBusy()) speechRecognizer.resume()
        }
    }

    override fun onDestroy() {
        speechRecognizer.shutdown()
        taskEngine.shutdown()
        JarvisRuntime.setServiceRunning(false)
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Listening…")
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
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content.take(80))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "JarvisFgService"
        private const val CHANNEL_ID = "jarvis_voice"
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
