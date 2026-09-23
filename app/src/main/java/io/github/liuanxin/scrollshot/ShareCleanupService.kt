package io.github.liuanxin.scrollshot

import android.app.job.JobParameters
import android.app.job.JobService

/** 分享目标可能异步读取图片, 延后清理缓存, 不写入相册. */
class ShareCleanupService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            ShareProvider.clean(this)
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
