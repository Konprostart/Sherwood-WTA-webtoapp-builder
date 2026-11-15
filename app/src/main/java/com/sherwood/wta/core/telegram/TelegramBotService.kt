package com.sherwood.wta.core.telegram

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sherwood.wta.ui.MainActivity
import com.sherwood.wta.R
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.studio.BuildEvents
import com.sherwood.wta.core.studio.BuildFinishedEvent
import com.sherwood.wta.util.SafeNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Telegram command centre alive.
 *
 * Why a foreground service rather than work scheduled on app start: the whole point of
 * the harness is to be reachable while the app is in the background and the phone is in
 * a pocket. Long polling is a persistent connection, which only a foreground service may
 * hold for hours without being killed.
 *
 * The service never stores the token itself — it asks [TelegramBotTokenStore] on every
 * reconnection, so clearing the token in settings also ends a running poller.
 */
class TelegramBotService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    private val settings by lazy { TelegramSettings.getInstance(applicationContext) }
    private val tokenStore by lazy { TelegramBotTokenStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startForeground(FOREGROUND_ID, buildNotification(settings.botUsername))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!settings.enabled) {
            // A start without the switch on means the user disabled it while the service
            // was running (or a stale restart landed here). Nothing to poll.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!tokenStore.hasToken()) {
            AppLogger.w(TAG, "Telegram service started without a stored token")
            stopSelf()
            return START_NOT_STICKY
        }
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ polling

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            // Build-finished and command replies share one sender so the poll loop is
            // never blocked by an upload.
            launch { TelegramNotifications.outbox.collect { reply -> sendToPaired(reply) } }
            launch {
                BuildEvents.events.collect { event -> notifyBuildFinished(event) }
            }
            pollLoop()
        }
    }

    private suspend fun pollLoop() {
        val router = TelegramCommandRouter(applicationContext, scope)
        var networkBackoffMs = INITIAL_BACKOFF_MS

        while (scope.isActive) {
            val token = tokenStore.currentToken()
            if (token == null) {
                AppLogger.w(TAG, "Telegram bot token is unavailable; stopping the poller")
                publishStatus(Strings.telegramTokenMissing)
                break
            }
            val api = TelegramApiClient(token)

            val identity = api.getMe()
            if (identity.isSuccess) {
                val username = identity.getOrNull()?.username
                settings.botUsername = username
                updateNotification(username)
                networkBackoffMs = INITIAL_BACKOFF_MS
            } else {
                val failure = (identity.exceptionOrNull() as? TelegramException)?.failure
                if (failure is TelegramFailure.InvalidToken) {
                    // Retrying cannot fix a revoked token; stop and say why.
                    settings.enabled = false
                    publishStatus(Strings.telegramTokenInvalid)
                    break
                }
                delay(networkBackoffMs)
                networkBackoffMs = (networkBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }

            var stop = false
            while (!stop && scope.isActive) {
                val offset = settings.lastUpdateId + 1
                val updates = api.getUpdates(offset)
                if (updates.isSuccess) {
                    networkBackoffMs = INITIAL_BACKOFF_MS
                    for (update in updates.getOrDefault(emptyList())) {
                        // Advance the offset before handling, so a crash mid-reply
                        // cannot replay the same command on restart.
                        settings.lastUpdateId = update.updateId
                        handleUpdate(api, router, update)
                    }
                } else {
                    val failure = (updates.exceptionOrNull() as? TelegramException)?.failure
                    when (failure) {
                        is TelegramFailure.InvalidToken -> {
                            settings.enabled = false
                            publishStatus(Strings.telegramTokenInvalid)
                            stop = true
                        }
                        is TelegramFailure.Conflict -> {
                            // Another poller owns getUpdates; back off rather than
                            // fight it for the offset.
                            delay(CONFLICT_BACKOFF_MS)
                        }
                        is TelegramFailure.RateLimited -> {
                            delay(failure.retryAfterSeconds.coerceIn(1, 60) * 1000L)
                        }
                        TelegramFailure.Cancelled -> stop = true
                        else -> {
                            delay(networkBackoffMs)
                            networkBackoffMs = (networkBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        }
                    }
                }
            }
            if (stop) break
        }
    }

    private suspend fun handleUpdate(
        api: TelegramApiClient,
        router: TelegramCommandRouter,
        update: TelegramUpdate
    ) {
        val message = update.message ?: return
        // Ignore the bot itself and other bots: a bot account can post to a paired chat,
        // and echoing our own reply back would loop.
        val from = message.from
        if (from != null && from.username == settings.botUsername) return
        if (message.text?.isBlank() == true) return
        val chatId = message.chat?.id ?: return
        val reply = try {
            router.route(message)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Command handler failed", e)
            TelegramReply.Text(Strings.telegramNetworkError)
        }
        send(api, chatId, reply)
    }

    private suspend fun sendToPaired(reply: TelegramReply) {
        val token = tokenStore.currentToken() ?: return
        if (reply is TelegramReply.Ignore) return
        val chats = settings.pairedChats
        if (chats.isEmpty()) return
        val api = TelegramApiClient(token)
        for (chatId in chats) send(api, chatId, reply)
    }

    private suspend fun send(api: TelegramApiClient, chatId: Long, reply: TelegramReply) {
        when (reply) {
            is TelegramReply.Ignore -> Unit
            is TelegramReply.Text -> {
                dispatch(api.sendMessage(chatId, reply.text))
            }
            is TelegramReply.Document -> {
                val sent = api.sendDocument(chatId, reply.file, reply.caption)
                if (sent.isFailure) {
                    // Uploads fail for reasons a chat can act on (too large, blocked),
                    // and a silent failure would look like the command was ignored.
                    dispatch(
                        api.sendMessage(
                            chatId,
                            reply.caption.ifBlank { reply.file.name }
                        )
                    )
                }
            }
        }
    }

    private fun dispatch(result: Result<Unit>) {
        result.exceptionOrNull()?.let { error ->
            val failure = (error as? TelegramException)?.failure
            AppLogger.w(TAG, "Telegram send failed: ${failure ?: error.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ notifications

    private fun notifyBuildFinished(event: BuildFinishedEvent) {
        val chats = settings.pairedChats
        if (chats.isEmpty()) return
        val conclusion = event.conclusion ?: if (event.succeeded) "success" else "failure"
        val text = buildString {
            append(Strings.telegramNotifyBuild(event.repository, event.branch, conclusion, event.runId))
            if (event.artifactNames.isNotEmpty()) {
                append('\n')
                append(Strings.telegramNotifyArtifacts(event.artifactNames.size))
            }
            if (!event.downloadError.isNullOrBlank()) {
                append('\n')
                append(event.downloadError)
            }
        }
        // The collector on TelegramNotifications already owns delivery; pushing through
        // it keeps one send path instead of two racing ones.
        TelegramNotifications.publish(TelegramReply.Text(text))
    }

    private fun publishStatus(text: String) {
        TelegramNotifications.publish(TelegramReply.Text(text))
    }

    private fun createChannel() {
        SafeNotificationChannels.ensure(
            context = this,
            id = CHANNEL_ID,
            name = Strings.telegramBot,
            importance = NotificationManager.IMPORTANCE_LOW,
            description = Strings.telegramDesc
        )
    }

    private fun buildNotification(username: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TelegramBotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (username.isNullOrBlank()) {
            Strings.telegramBot
        } else {
            "@$username"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(Strings.telegramServiceRunning)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, Strings.telegramStop, stop)
            .build()
    }

    private fun updateNotification(username: String?) {
        runCatching {
            NotificationManagerCompat.from(this).notify(FOREGROUND_ID, buildNotification(username))
        }
    }

    companion object {
        private const val TAG = "TelegramBotService"
        private const val CHANNEL_ID = "telegram_bot_channel"
        private const val FOREGROUND_ID = 10011
        private const val ACTION_STOP = "com.sherwood.wta.action.STOP_TELEGRAM_BOT"
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val CONFLICT_BACKOFF_MS = 30_000L

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        /** Starts the poller if the user enabled it and a token is stored. */
        fun start(context: Context) {
            val settings = TelegramSettings.getInstance(context)
            val store = TelegramBotTokenStore(context)
            if (!settings.enabled || !store.hasToken()) return
            val intent = Intent(context, TelegramBotService::class.java)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Could not start the Telegram bot service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TelegramBotService::class.java))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Could not stop the Telegram bot service", e)
            }
        }

        /**
         * Re-arms the poller after the process was recreated.
         *
         * Called from the application scope, never from a UI callback: a bot that only
         * worked while some screen was open would not be a command centre.
         */
        fun restoreIfEnabled(context: Context) {
            if (isRunning()) return
            start(context)
        }
    }
}
