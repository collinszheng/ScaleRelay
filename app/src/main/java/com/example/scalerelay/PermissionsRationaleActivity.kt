package com.example.scalerelay

import android.os.Bundle
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Health Connect 的权限说明页。
 *
 * **这不是可选的装饰页。** HC 在弹出授权页之前会解析
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`；解析不到就 `finish()`，
 * 于是「点授权那一行什么也没发生」。这个问题在 WeightDiary 上踩过一次
 * （HC 自己的日志：`E PermissionsActivity: App should support rationale intent, finishing!`）。
 *
 * 用原生 View 而不是 Compose：这个页面可能被 HC 在任意时机拉起，
 * 要走最短的路径渲染出来，不值得为它装一整套 Compose 组合。
 */
class PermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = getString(R.string.rationale_body)
            textSize = 16f
            setPadding(64, 160, 64, 64)
            gravity = Gravity.START
            setLineSpacing(0f, 1.3f)
        }

        setContentView(ScrollView(this).apply { addView(text) })
    }
}
