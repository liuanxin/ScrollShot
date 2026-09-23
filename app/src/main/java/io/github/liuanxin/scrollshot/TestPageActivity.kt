package io.github.liuanxin.scrollshot

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 固定标题、连续编号和固定底栏, 用于真机核对漏行、重复和起点. */
class TestPageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value
            textSize = size
            setPadding(24, 24, 24, 24)
            setTextColor(Color.BLACK)
        }
        root.addView(label("←  长截图测试 / 起点标题", 22f))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (index in 1..100) {
            content.addView(label("第 %03d 段 | %s\n这里是用于核对长截图的连续正文. 开头不丢失, 中间不重复, 结尾可以准确停止.\n校验: %08x".format(index, if (index % 2 == 0) { "山川与河流" } else { "城市与星空" }, index * 7919), 17f).apply {
                setBackgroundColor(if (index % 2 == 0) { 0xffeef4f1.toInt() } else { Color.WHITE })
            })
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("固定底栏 / 仅在末尾保留一次", 14f))
        setContentView(root)
    }
}
