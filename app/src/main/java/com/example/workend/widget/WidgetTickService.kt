package com.example.workend.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.workend.R
import com.example.workend.data.SettingsRepository
import com.example.workend.logic.WorkCalculator
import com.example.workend.logic.WorkCalculator.Phase
import java.time.LocalDateTime

/**
 * 桌面卡片秒级刷新前台服务。
 *
 * RemoteViews 没有逐秒改文字的系统机制（Chronometer 只能显示时间格式），
 * 唯一的办法是由前台服务每秒更新一次卡片。设计要点：
 * - 每秒全量渲染；秒级模式下倒计时用静态文本逐秒刷新，完全不使用
 *   Chronometer——频繁重设它的 base 会与其内部 ticker 踩踏导致倒计时卡死；
 * - 全量更新自带完整状态，桌面启动器任何时候重建视图都不会丢内容；
 * - 仅在工作时段（上午/下午）运行，午休、下班后、休息日自动停止；
 * - 停止后由边界精确闹钟在下一个计薪时段把它拉起；若服务被系统/厂商
 *   省电策略中途杀死，Provider 的看门狗闹钟会自动把它复活。
 */
class WidgetTickService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: SettingsRepository

    private val tick = object : Runnable {
        override fun run() {
            val context = this@WidgetTickService
            val settings = repository.current
            val phase = WorkCalculator.workCountdown(LocalDateTime.now(), settings).phase
            val earning = phase == Phase.MORNING || phase == Phase.AFTERNOON
            if (!earning || !WorkEndWidgetProvider.isPlaced(context)) {
                // 不再计薪或卡片已被移除：渲染最终状态后自停，
                // 下一个边界闹钟（上班/午休结束）会重新拉起服务
                WorkEndWidgetProvider.renderAll(context, settings)
                stopSelf()
                return
            }
            // 正常计薪中：每秒全量渲染（秒级模式下倒计时也是静态文本，
            // 不碰 Chronometer）。全量更新保证任意时刻重建视图都不丢内容。
            WorkEndWidgetProvider.renderAll(context, settings)
            // 对齐到整秒，避免显示值漂移
            handler.postDelayed(this, 1_000 - System.currentTimeMillis() % 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        repository = SettingsRepository(applicationContext)
        startForegroundCompat()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌面卡片秒级刷新", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("下班倒数")
            .setContentText("桌面卡片薪资秒级刷新中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        /** 服务是否存活：同进程内可见，供卡片看门狗判断是否需要拉起 */
        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "widget_tick"
        private const val NOTIFICATION_ID = 1001
    }
}
