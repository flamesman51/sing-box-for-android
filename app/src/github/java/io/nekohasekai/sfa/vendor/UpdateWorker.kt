package io.nekohasekai.sfa.vendor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.update.ServerUpdateWork

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        fun schedule(context: Context) = ServerUpdateWork.schedule(context)
    }

    override suspend fun doWork(): Result = ServerUpdateWork.runCheck(applicationContext)
}
