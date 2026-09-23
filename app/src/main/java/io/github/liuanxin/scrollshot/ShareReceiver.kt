package io.github.liuanxin.scrollshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** 目标选择回调独立于编辑页生命周期; 取消后的旧事务不再生效. */
class ShareReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        val state = context.getSharedPreferences("share", Context.MODE_PRIVATE)
        val id = state.getString(token, null) ?: return
        if (id.isEmpty() || !id.all { it.isDigit() }) { return }
        state.edit().putString(token, "chosen").commit()
        context.sendBroadcast(Intent("${context.packageName}.SHARE_SELECTED").setPackage(context.packageName).putExtra("token", token))
        val pending = goAsync()
        Thread {
            try { File(context.filesDir, "captures/$id").deleteRecursively() } finally { pending.finish() }
        }.start()
    }
}
