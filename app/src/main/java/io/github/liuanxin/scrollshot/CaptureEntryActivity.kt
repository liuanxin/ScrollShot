package io.github.liuanxin.scrollshot

import android.app.Activity
import android.os.Bundle

class CaptureEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先退出透明入口, 等快捷面板和入口窗口都消失后才锁定截图起点.
        finish()
        CaptureService.instance?.requestStart()
    }
}
