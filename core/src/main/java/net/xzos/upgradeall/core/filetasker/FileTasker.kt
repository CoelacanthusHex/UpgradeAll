package net.xzos.upgradeall.core.filetasker

import android.content.Context
import net.xzos.upgradeall.core.downloader.DownloadOb
import net.xzos.upgradeall.core.downloader.Downloader
import net.xzos.upgradeall.core.downloader.PreDownload
import net.xzos.upgradeall.core.installer.ApkInstaller
import net.xzos.upgradeall.core.installer.isApkFile
import net.xzos.upgradeall.core.module.app.FileAsset
import net.xzos.upgradeall.core.utils.coroutines.CoroutinesCount
import net.xzos.upgradeall.core.utils.openInFileManager

class FileTasker internal constructor(internal val fileAsset: FileAsset) {
    val id: Int = getTaskerIndex()
    val name = fileAsset.name

    /* 预下载器 */
    private var preDownload: PreDownload? = PreDownload(fileAsset)

    /* 下载管理器 */
    var downloader: Downloader? = null

    val installable: Boolean
        get() = downloader?.downloadDir?.isApkFile() ?: false

    suspend fun install(failedInstallObserverFun: (Throwable) -> Unit, completeInstallFunc: () -> Unit) {
        if (installable) {
            downloader?.getFileList()?.run {
                when (this.size) {
                    0 -> return
                    1 -> {
                        ApkInstaller.install(this[0],
                                fun(e) { failedInstallObserverFun(e) },
                                fun(_) { completeInstallFunc() }
                        )
                    }
                    else -> {
                        ApkInstaller.multipleInstall(
                                downloader!!.downloadDir,
                                fun(e) { failedInstallObserverFun(e) },
                                fun(_) { completeInstallFunc() }
                        )
                    }
                }
            }
        }
    }

    suspend fun startDownload(
            taskStartedFun: (Int) -> Unit,
            taskStartFailedFun: () -> Unit,
            vararg downloadOb: DownloadOb,
    ) {
        if (downloader == null) {
            val overrideFailFun = {
                taskStartFailedFun()
                FileTaskerManager.removeFileTasker(this)
            }
            downloader = preDownload?.startDownload(taskStartedFun, overrideFailFun, *downloadOb)
            preDownload = null
        }
    }

    fun resume() = downloader?.resume()
    fun pause() = downloader?.pause()
    fun retry() = downloader?.retry()
    fun cancel() = downloader?.cancel() ?: preDownload?.cancel()

    fun openDownloadDir(context: Context) {
        downloader?.downloadDir?.path?.run {
            openInFileManager(this, context)
        }
    }

    companion object {
        private val TASKER_INDEX = CoroutinesCount(0)
        private fun getTaskerIndex(): Int = TASKER_INDEX.up()

        fun FileAsset.getFileTasker() = FileTasker(this).also {
            FileTaskerManager.addFileTasker(it)
        }
    }
}