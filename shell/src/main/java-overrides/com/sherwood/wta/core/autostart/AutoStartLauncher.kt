package com.sherwood.wta.core.autostart

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.sherwood.wta.WebToAppApplication
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.ui.shell.ShellActivity

object AutoStartLauncher {

    private const val TAG = "AutoStartLauncher"

    fun launch(
        context: Context,
        source: String,
        appId: Long = -1,
        delayMs: Long = 0
    ) {
        val action = Runnable {
            try {
                val intent = Intent(context, ShellActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppLogger.d(TAG, "[$source] Shell 应用已启动")
            } catch (e: Exception) {
                AppLogger.e(TAG, "[$source] 启动 Shell 应用失败", e)
            }
        }

        if (delayMs > 0) {
            AppLogger.d(TAG, "[$source] 将在 ${delayMs}ms 后启动")
            Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
        } else {
            action.run()
        }
    }
}
