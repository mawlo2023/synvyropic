package de.synvyropic.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import de.synvyropic.app.MainActivity
import de.synvyropic.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hält die App während des Exports im Vordergrund (Benachrichtigung mit Fortschritt),
 * damit der Export auch bei ausgeschaltetem Bildschirm oder in einer anderen App weiterläuft.
 * Die eigentliche Arbeit macht der [ExportManager].
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID_PROGRESS, progress(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            ExportManager.state
                .map { s -> if (s is ExportState.Running) ExportState.Running(s.projectId, (s.progress * 100).toInt() / 100f) else s }
                .distinctUntilChanged()
                .collect { s ->
                    when (s) {
                        is ExportState.Running -> nm.notify(ID_PROGRESS, progress((s.progress * 100).toInt()))
                        is ExportState.Done -> { nm.notify(ID_RESULT, result("Video fertig 🎬", "Antippen zum Ansehen", s.uri)); stop() }
                        is ExportState.Failed -> { nm.notify(ID_RESULT, result("Export fehlgeschlagen", s.message, null)); stop() }
                        ExportState.Idle -> stop()
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progress(percent: Int): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Synvyropic exportiert …")
        .setContentText("$percent %")
        .setProgress(100, percent, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    private fun result(title: String, text: String, uri: android.net.Uri?): Notification {
        val intent = if (uri != null) {
            PendingIntent.getActivity(
                this, 1,
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else openApp()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val ID_PROGRESS = 1
        private const val ID_RESULT = 2
    }
}
