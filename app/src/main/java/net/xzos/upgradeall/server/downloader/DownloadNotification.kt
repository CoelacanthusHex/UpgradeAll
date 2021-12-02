package net.xzos.upgradeall.server.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tonyodev.fetch2.Download
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import net.xzos.upgradeall.R
import net.xzos.upgradeall.application.MyApplication
import net.xzos.upgradeall.core.androidutils.FlagDelegate
import net.xzos.upgradeall.core.downloader.filetasker.*
import net.xzos.upgradeall.core.installer.FileType
import net.xzos.upgradeall.core.utils.coroutines.CoroutinesCount
import net.xzos.upgradeall.core.utils.coroutines.runWithLock
import net.xzos.upgradeall.core.utils.log.msg
import net.xzos.upgradeall.data.PreferencesMap
import net.xzos.upgradeall.utils.file.fileName
import net.xzos.upgradeall.utils.getNotificationManager
import net.xzos.upgradeall.wrapper.download.*

class DownloadNotification(private val fileTaskerId: FileTaskerId) {

    private val notificationIndex: Int = getNotificationIndex()

    private val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID).apply {
        priority = NotificationCompat.PRIORITY_LOW
    }

    init {
        createNotificationChannel()
    }

    private var closed = false

    private val mutex = Mutex()

    fun registerNotify(wrapper: FileTaskerWrapper) {
        DownloadNotificationManager.addNotification(wrapper.id.toString(), this)
        wrapper.observeWithChecker(DownloadStatus.DOWNLOAD_INFO_RENEW, { snap: FileTaskerSnap ->
            mutex.runWithLock { preDownload(snap.statusMsg, DownloadStatus.DOWNLOAD_INFO_RENEW) }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(DownloadStatus.TASK_WAIT_START, {
            mutex.runWithLock { preDownload(wrapper.name, DownloadStatus.TASK_WAIT_START) }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(DownloadStatus.TASK_START_FAIL, { snap: FileTaskerSnap ->
            mutex.runWithLock { taskFailed(snap.error) }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(DownloadStatus.EXTERNAL_DOWNLOAD, {
            mutex.runWithLock { taskCancel() }
        }, { !closed }, { closed })

        wrapper.observeWithChecker(DownloadStatus.TASK_STARTED, { snap: FileTaskerSnap ->
            mutex.runWithLock { taskStart(snap.statusMsg.toInt()) }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(FileTaskerStatus.DOWNLOAD_START, { snap: FileTaskerSnap ->
            mutex.runWithLock { taskRunning(snap.download ?: return@runWithLock) }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(FileTaskerStatus.DOWNLOAD_RUNNING, { snap: FileTaskerSnap ->
            mutex.runWithLock { taskRunning(snap.download ?: return@runWithLock) }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(FileTaskerStatus.DOWNLOAD_STOP, { snap: FileTaskerSnap ->
            mutex.runWithLock { taskPause() }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(FileTaskerStatus.DOWNLOAD_COMPLETE, { snap: FileTaskerSnap ->
            mutex.runWithLock { taskComplete(snap.download ?: return@runWithLock) }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(FileTaskerStatus.DOWNLOAD_CANCEL, {
            mutex.runWithLock { taskCancel() }
        }, { !closed }, { closed })
        wrapper.observeWithChecker(FileTaskerStatus.DOWNLOAD_FAIL, {
            mutex.runWithLock { taskFail() }
        }, { !closed }, { closed })
    }

    private fun preDownload(taskName: String, status: DownloadStatus) {
        builder.clearActions()
            .setOngoing(true)
            .setContentTitle("${getString(R.string.file_download)}: $taskName")
            .setContentText("${getString(R.string.waiting_pre_process)}: ${status.msg}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(0, PROGRESS_MAX, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_CANCEL)
            )
        notificationNotify()
    }

    private fun taskFailed(e: Throwable?) {
        builder.clearActions()
            .setNotificationCanGoing()
            .setContentText(e?.msg() ?: "unknown error")
        notificationNotify()
    }

    fun showInstallNotification(apkFileName: String) {
        builder.clearActions().run {
            setContentTitle("${getString(R.string.installing)}: $apkFileName")
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setProgress(0, 0, false)
            setDeleteIntent(getSnoozePendingIntent(DownloadBroadcastReceiver.NOTIFY_CANCEL))
            setNotificationCanGoing()
        }
        // TODO: 更全面的安装过程检测
        // 安装失败后回退操作
        notificationNotify()
    }

    private fun taskStart(downloadId: Int) {
        builder.clearActions()
            .setContentText("download id: $downloadId")
            .addAction(
                android.R.drawable.ic_media_pause, getString(R.string.pause),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_CANCEL)
            )
        notificationNotify()
    }

    private fun taskRunning(task: Download) {
        val progressCurrent: Int = task.progress
        val speed = getSpeedText(task)
        builder.clearActions()
            .setContentTitle("${getString(R.string.file_download)}: ${task.file.fileName}")
            .setContentText(speed)
            .setProgress(PROGRESS_MAX, progressCurrent, false)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .addAction(
                android.R.drawable.ic_media_pause, getString(R.string.pause),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_CANCEL)
            )
        notificationNotify()
    }

    private fun taskPause() {
        builder.clearActions()
            .setContentText(getString(R.string.download_paused))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .addAction(
                android.R.drawable.ic_media_pause, getString(R.string.Continue),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_CONTINUE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_CANCEL)
            )
            .setProgress(0, 0, false)
        notificationNotify()
    }

    private fun taskCancel() {
        cancelNotification()
    }

    private fun taskFail() {
        val delTaskSnoozePendingIntent =
            getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_CANCEL)
        builder.clearActions()
            .setContentText(getString(R.string.download_failed_click_to_retry))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(0, 0, false)
            .setContentIntent(getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_RETRY))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                delTaskSnoozePendingIntent
            )
            .setDeleteIntent(delTaskSnoozePendingIntent)
            .setNotificationCanGoing()
        notificationNotify()
    }

    private fun taskComplete(download: Download) {
        val fileTasker = fileTaskerManagerWrapper.getFileTasker(fileTaskerId)!!
        val fileType = fileTasker.fileType
        showManualMenuNotification(download, fileType)
        if (fileType != FileType.UNKNOWN && PreferencesMap.auto_install) {
            GlobalScope.launch {
                installFileTasker(
                    context, fileTasker, this@DownloadNotification
                )
            }
        }
    }

    private fun showManualMenuNotification(download: Download, fileType: FileType?) {
        builder.clearActions().run {
            val filePath = download.file
            setContentTitle("${getString(R.string.download_complete)}: ${filePath.fileName}")
            val contentText =
                "${getString(R.string.file_path)}: $filePath"
            setContentText(contentText)
            setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
            )
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setProgress(0, 0, false)
            setNotificationCanGoing()
            runBlocking {
                if (fileType != null) {
                    val extraText = when (fileType) {
                        FileType.APK -> getString(R.string.apk)
                        FileType.MAGISK_MODULE -> getString(R.string.magisk_module)
                        else -> ""
                    }
                    addAction(
                        R.drawable.ic_check_mark_circle,
                        "${getString(R.string.install)} $extraText",
                        getSnoozePendingIntent(DownloadBroadcastReceiver.INSTALL_APK)
                    )
                }
            }
            addAction(
                android.R.drawable.stat_sys_download_done, getString(R.string.open_file),
                getSnoozePendingIntent(DownloadBroadcastReceiver.OPEN_FILE)
            )
            addAction(
                android.R.drawable.ic_menu_delete,
                getString(R.string.delete),
                getSnoozePendingIntent(DownloadBroadcastReceiver.DOWNLOAD_CANCEL)
            )
            setDeleteIntent(getSnoozePendingIntent(DownloadBroadcastReceiver.NOTIFY_CANCEL))
        }
        notificationNotify()
    }

    private fun getString(@StringRes resId: Int): CharSequence = context.getString(resId)

    private fun notificationNotify() {
        notificationNotify(notificationIndex, builder.build())
    }

    fun cancelNotification() {
        closed = true
        NotificationManagerCompat.from(context).cancel(notificationIndex)
        DownloadNotificationManager.removeNotification(this)
    }

    private fun getSnoozeIntent(extraIdentifierDownloadControlId: Int): Intent {
        return Intent(context, DownloadBroadcastReceiver::class.java).apply {
            action = DownloadBroadcastReceiver.ACTION_SNOOZE
            putExtra(
                DownloadBroadcastReceiver.EXTRA_IDENTIFIER_FILE_TASKER_CONTROL,
                extraIdentifierDownloadControlId
            )
            putExtra(
                DownloadBroadcastReceiver.EXTRA_IDENTIFIER_FILE_TASKER_ID,
                fileTaskerId.toString()
            )
        }
    }

    private fun getSnoozePendingIntent(extraIdentifierDownloadControlId: Int): PendingIntent {
        val snoozeIntent = getSnoozeIntent(extraIdentifierDownloadControlId)
        val flags =
            if (extraIdentifierDownloadControlId == DownloadBroadcastReceiver.INSTALL_APK ||
                extraIdentifierDownloadControlId == DownloadBroadcastReceiver.OPEN_FILE
            )
            // 保存文件/安装按钮可多次点击
                0
            else PendingIntent.FLAG_ONE_SHOT
        return PendingIntent.getBroadcast(
            context,
            getPendingIntentIndex(),
            snoozeIntent,
            flags or FlagDelegate.PENDING_INTENT_FLAG_IMMUTABLE
        )
    }

    private fun getSpeedText(task: Download): String {
        val speed = task.downloadedBytesPerSecond
        return when {
            speed == -1L -> "0 b/s"
            speed < 1024L -> "$speed b/s"
            1024L <= speed && speed < 1024 * 1024L -> "${speed / 1024} kb/s"
            1024 * 1024L <= speed -> "${speed / (1024 * 1024)} mb/s"
            else -> ""
        }
    }

    private fun NotificationCompat.Builder.setNotificationCanGoing(): NotificationCompat.Builder {
        setDeleteIntent(getSnoozePendingIntent(DownloadBroadcastReceiver.NOTIFY_CANCEL))
        setOngoing(false)
        return this
    }

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "DownloadNotification"
        private const val PROGRESS_MAX = 100
        private val context get() = MyApplication.context
        private var initNotificationChannel = false

        private const val DOWNLOAD_SERVICE_NOTIFICATION_INDEX = 200
        private val NOTIFICATION_INDEX = CoroutinesCount(201)
        private fun getNotificationIndex(): Int = NOTIFICATION_INDEX.up()

        private val PENDING_INTENT_INDEX = CoroutinesCount(0)
        private fun getPendingIntentIndex(): Int = PENDING_INTENT_INDEX.up()

        private val downloadServiceNotificationBuilder
            get() = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setContentTitle(getString(R.string.download_service_running))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .apply { priority = NotificationCompat.PRIORITY_LOW }

        val downloadServiceNotificationMaker = fun(): Pair<Int, Notification> {
            return getDownloadServiceNotification()
        }

        private fun getDownloadServiceNotification(): Pair<Int, Notification> {
            return Pair(
                DOWNLOAD_SERVICE_NOTIFICATION_INDEX,
                notificationNotify(
                    DOWNLOAD_SERVICE_NOTIFICATION_INDEX,
                    downloadServiceNotificationBuilder.build()
                )
            )
        }

        private fun notificationNotify(
            notificationId: Int,
            notification: Notification
        ): Notification {
            if (!initNotificationChannel) {
                createNotificationChannel()
                initNotificationChannel = true
            }
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            return notification
        }

        fun createNotificationChannel() {
            val notificationManager = getNotificationManager(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && notificationManager.getNotificationChannel(DOWNLOAD_CHANNEL_ID) == null
            ) {
                val channel = NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    getString(R.string.file_download),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = getString(R.string.show_download_status)
                channel.enableLights(true)
                channel.enableVibration(false)
                channel.setShowBadge(false)
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun getString(@StringRes res: Int): String = context.getString(res)
    }
}
