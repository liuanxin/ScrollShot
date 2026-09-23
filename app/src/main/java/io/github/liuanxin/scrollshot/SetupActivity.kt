package io.github.liuanxin.scrollshot

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp(), 24.dp(), 28.dp(), 24.dp())
        }
        body.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(28.dp(), bars.top + 24.dp(), 28.dp(), bars.bottom + 24.dp())
            insets
        }
        fun text(value: String, size: Float): TextView {
            return TextView(this).apply {
                text = value
                textSize = size
                setTextColor(Color.rgb(30, 48, 43))
                setPadding(0, 8.dp(), 0, 14.dp())
                body.addView(this)
            }
        }
        text("长截图", 30f)
        text("从眼前这一屏开始", 20f)
        text("下拉快捷设置, 点长截图. 页面自动向下滚动, 点击顶部提示条停止, 裁剪后保存.\n\n不显示桌面图标, 没有常驻悬浮按钮.", 16f)
        text("首次设置", 20f)
        status = text("", 15f)
        text("开启无障碍服务后, 工具仅在你主动开始截图时读取画面和滚动页面. 图片只在本机处理, 不联网. 可随时在系统设置关闭.\n\n若系统提示受限设置, 在应用信息右上角菜单允许受限设置后再开启.", 14f)
        fun button(title: String, action: () -> Unit) {
            body.addView(Button(this).apply { text = title; setOnClickListener { action() } })
        }
        button("1. 开启长截图服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. 添加快捷设置按钮") {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, CaptureTileService::class.java), "长截图",
                Icon.createWithResource(this, R.drawable.ic_capture), mainExecutor
            ) { result ->
                status.text = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    "按钮已添加. 下拉快捷设置即可使用."
                } else { "请下拉快捷设置, 点编辑, 将长截图拖入按钮区." }
            }
        }
        button("打开本机测试长文") { startActivity(Intent(this, TestPageActivity::class.java)) }
        val drafts = java.io.File(filesDir, "captures").listFiles()?.filter { java.io.File(it, "document.json").isFile }?.sortedByDescending { it.name }
        if (!drafts.isNullOrEmpty()) {
            button("恢复上次未保存的截图") {
                startActivity(Intent(this, EditorActivity::class.java).putExtra("capture", drafts.first().name))
            }
        }
        button("完成") { finish() }
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (CaptureService.instance == null) { "服务尚未开启" } else { "服务已开启, 可从快捷设置开始截图" }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
