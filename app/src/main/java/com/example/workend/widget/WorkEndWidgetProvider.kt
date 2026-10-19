package com.example.workend.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.workend.R
import com.example.workend.data.SettingsRepository
import com.example.workend.data.WorkSettings
import com.example.workend.logic.WorkCalculator
import com.example.workend.logic.WorkCalculator.Phase
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 桌面小组件：上班日程卡片（4 列 × 2 行）。
 *
 * 刷新策略：
 * - 倒计时秒级跳动由 Chronometer（倒计时模式）系统驱动，无需轮询；
 * - 开启「薪资秒级跳动」时，工作时段内由 [WidgetTickService] 前台服务每秒渲染；
 *   看门狗闹钟负责把服务拉起（后台限制下延迟数秒）并在其被杀后自动复活；
 * - 未开启时，工作时段内用每分钟一次的非唤醒闹钟刷新薪资；
 * - 阶段边界（上班/午休/下午/下班/零点/下一个工作日）用精确闹钟刷新；
 * - 系统兜底每 30 分钟刷新一次（updatePeriodMillis）；
 * - 应用内修改配置后通过 [refreshAll] 立即同步。
 */
class WorkEndWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val settings = SettingsRepository(context).current
        for (appWidgetId in appWidgetIds) {
            renderOne(context, manager, appWidgetId, settings)
        }
        scheduleNextRefresh(context, settings)
        syncTickService(context, settings)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // APPWIDGET_UPDATE 已由父类转给 onUpdate
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED -> {
                val settings = SettingsRepository(context).current
                if (renderAll(context, settings)) {
                    scheduleNextRefresh(context, settings)
                    syncTickService(context, settings)
                }
            }

            ACTION_TICK_WATCHDOG -> onTickWatchdog(context, intent)
        }
    }

    /**
     * 秒级服务看门狗触发：
     * - 服务没在跑：补一帧渲染保持卡片新鲜，并拉起服务——闹钟广播会给
     *   应用临时豁免，此时后台启动前台服务是合法的；随后按退避继续自检。
     * - 服务在跑：只续排下一次自检，作为服务被系统/厂商杀掉后的自动复活机制。
     */
    private fun onTickWatchdog(context: Context, intent: Intent) {
        val settings = SettingsRepository(context).current
        if (!needTick(context, settings)) return // 不再计薪：看门狗自然终止
        if (!WidgetTickService.isRunning) {
            renderAll(context, settings)
            startTickService(context)
            scheduleWatchdog(context, settings, intent.getIntExtra(EXTRA_WATCHDOG_ATTEMPT, 0) + 1)
        } else {
            scheduleWatchdog(context, settings, 0)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // 最后一个卡片被移除时取消闹钟并停掉秒级刷新服务
        val manager = AppWidgetManager.getInstance(context)
        val remaining = manager.getAppWidgetIds(
            ComponentName(context, WorkEndWidgetProvider::class.java),
        )
        if (remaining.isEmpty()) {
            context.getSystemService(AlarmManager::class.java)
                ?.cancel(refreshPendingIntent(context))
            cancelWatchdog(context)
            context.stopService(Intent(context, WidgetTickService::class.java))
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.workend.widget.REFRESH"
        private const val ACTION_TICK_WATCHDOG = "com.example.workend.widget.TICK_WATCHDOG"
        private const val EXTRA_WATCHDOG_ATTEMPT = "watchdog_attempt"

        /** 看门狗 PendingIntent 的 requestCode，与刷新闹钟（0）区分开 */
        private const val WATCHDOG_REQUEST_CODE = 2001

        /** 卡片是否已放置在桌面上 */
        fun isPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            return manager
                .getAppWidgetIds(ComponentName(context, WorkEndWidgetProvider::class.java))
                .isNotEmpty()
        }

        /**
         * 请求系统弹出「添加卡片」对话框（Android 8.0+）。
         * 返回是否成功发起了请求（用户仍可在对话框中取消）。
         */
        fun requestPin(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val manager = AppWidgetManager.getInstance(context) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context, WorkEndWidgetProvider::class.java)
            return manager.requestPinAppWidget(provider, null, null)
        }

        /** 渲染所有已放置的卡片；没有任何卡片时返回 false */
        fun renderAll(context: Context, settings: WorkSettings = SettingsRepository(context).current): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WorkEndWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return false
            for (appWidgetId in ids) {
                renderOne(context, manager, appWidgetId, settings)
            }
            return true
        }

        /** 应用内配置变化后立即刷新所有卡片 */
        fun refreshAll(context: Context) {
            val settings = SettingsRepository(context).current
            if (renderAll(context, settings)) {
                scheduleNextRefresh(context, settings)
                syncTickService(context, settings)
            }
        }

        /**
         * 根据开关与当前阶段决定秒级刷新服务的启停：
         * 仅在「开关打开 + 正处于计薪时段 + 已放置卡片」时运行。
         *
         * 注意：添加卡片/重装应用后，APPWIDGET_UPDATE 常在应用处于后台时
         * 到达，Android 12+ 禁止后台启动前台服务——直接启动会被拒绝，
         * 卡片就会一直冻结。因此无论启动是否被受理，都安排看门狗闹钟兜底：
         * 闹钟广播会给应用临时豁免，届时即可合法拉起服务。
         */
        fun syncTickService(context: Context, settings: WorkSettings) {
            if (needTick(context, settings)) {
                startTickService(context)
                scheduleWatchdog(context, settings, 0)
            } else {
                context.stopService(Intent(context, WidgetTickService::class.java))
                cancelWatchdog(context)
            }
        }

        /** 是否需要秒级服务：开关打开 + 已放置卡片 + 正处于计薪时段 */
        private fun needTick(context: Context, settings: WorkSettings): Boolean {
            val phase = WorkCalculator.workCountdown(LocalDateTime.now(), settings).phase
            return settings.secondsTick &&
                isPlaced(context) &&
                (phase == Phase.MORNING || phase == Phase.AFTERNOON)
        }

        /** 尝试启动秒级服务；返回系统是否受理（后台启动受限时会被拒绝） */
        private fun startTickService(context: Context): Boolean = try {
            val intent = Intent(context, WidgetTickService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (_: Exception) {
            false
        }

        private fun renderOne(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            settings: WorkSettings,
        ) {
            val now = LocalDateTime.now()
            val views = RemoteViews(context.packageName, R.layout.work_end_widget)
            val countdown = WorkCalculator.workCountdown(now, settings)

            // 标题行：周几 · 工作日/休息日
            val week = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
            val isWorkday = now.dayOfWeek in settings.workDays
            views.setTextViewText(
                R.id.widget_date_label,
                "$week · ${if (isWorkday) "工作日" else "休息日"}",
            )

            // 阶段标签
            views.setTextViewText(R.id.widget_phase_label, countdown.label)

            // 倒计时：
            // - 普通模式：Chronometer 设定一次绝对基准后自走（零轮询）；
            // - 秒级服务模式：完全不用 Chronometer（每秒重设 base 会与它内部
            //   ticker 相互踩踏导致倒计时卡死），改由服务每秒刷新静态文本，
            //   且每次更新携带完整状态，启动器重建视图也不会丢内容。
            val chronoTarget: LocalDateTime? = when (countdown.phase) {
                Phase.MORNING, Phase.AFTERNOON -> now.toLocalDate().atTime(settings.workEnd)
                Phase.LUNCH -> now.toLocalDate().atTime(settings.lunchEnd)
                Phase.BEFORE_WORK -> now.toLocalDate().atTime(settings.workStart)
                else -> null
            }
            if (chronoTarget != null && !settings.secondsTick) {
                val remainingMs = Duration.between(now, chronoTarget).toMillis()
                views.setViewVisibility(R.id.widget_countdown_chrono, View.VISIBLE)
                views.setViewVisibility(R.id.widget_countdown_static, View.GONE)
                views.setChronometerCountDown(R.id.widget_countdown_chrono, true)
                views.setChronometer(
                    R.id.widget_countdown_chrono,
                    SystemClock.elapsedRealtime() + remainingMs,
                    null,
                    true,
                )
            } else if (chronoTarget != null) {
                views.setViewVisibility(R.id.widget_countdown_chrono, View.GONE)
                views.setViewVisibility(R.id.widget_countdown_static, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_countdown_static,
                    WorkCalculator.formatRemaining(Duration.between(now, chronoTarget)),
                )
            } else {
                val target = WorkCalculator.nextWorkStart(now, settings)
                views.setViewVisibility(R.id.widget_countdown_chrono, View.GONE)
                views.setViewVisibility(R.id.widget_countdown_static, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_countdown_static,
                    WorkCalculator.formatRemainingLong(Duration.between(now, target)),
                )
            }

            // 距发薪日 / 距周末
            val daysToPayday = WorkCalculator.daysToPayday(now.toLocalDate(), settings.payday)
            val daysToWeekend = WorkCalculator.daysToWeekend(now.toLocalDate())
            views.setTextViewText(R.id.widget_payday_value, daysText(daysToPayday))
            views.setTextViewText(R.id.widget_weekend_value, daysText(daysToWeekend))

            // 当日薪资 + 状态说明（正常工作中隐藏说明，保持两行高度紧凑）
            views.setTextViewText(
                R.id.widget_salary_value,
                money(WorkCalculator.todayEarned(now, settings)),
            )
            val showCaption = countdown.phase != Phase.MORNING && countdown.phase != Phase.AFTERNOON
            views.setViewVisibility(
                R.id.widget_salary_caption,
                if (showCaption) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(
                R.id.widget_salary_caption,
                WorkCalculator.salaryCaption(countdown.phase, settings),
            )

            // 桌宠表情：上班中敲码 / 休息中打盹 / 已下班庆祝
            val petState = when (countdown.phase) {
                Phase.MORNING, Phase.AFTERNOON -> {
                    views.setImageViewResource(R.id.widget_pet, R.drawable.pet_working)
                    "上班中"
                }
                Phase.AFTER_WORK -> {
                    views.setImageViewResource(R.id.widget_pet, R.drawable.pet_off)
                    "已下班"
                }
                else -> {
                    views.setImageViewResource(R.id.widget_pet, R.drawable.pet_rest)
                    "休息中"
                }
            }
            views.setContentDescription(R.id.widget_pet, petState)

            manager.updateAppWidget(appWidgetId, views)
        }

        private fun scheduleNextRefresh(context: Context, settings: WorkSettings) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val now = LocalDateTime.now()
            val phase = WorkCalculator.workCountdown(now, settings).phase
            val pendingIntent = refreshPendingIntent(context)

            // 开启秒级服务时，工作时段由服务驱动，这里只排边界闹钟（用于在
            // 午休结束/下班等时刻重新同步服务）；未开启时工作时段内每分钟
            // 刷新一次薪资，其余阶段只在下一个边界刷新。
            // 用非唤醒型 RTC 闹钟：息屏期间不触发不耗电，亮屏后立即补齐。
            val triggerAtMillis: Long
            val wakeDevice: Boolean
            if (phase == Phase.MORNING || phase == Phase.AFTERNOON) {
                if (settings.secondsTick) {
                    triggerAtMillis = WorkCalculator.nextBoundary(now, settings)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    wakeDevice = true
                } else {
                    triggerAtMillis = System.currentTimeMillis() + 60_000
                    wakeDevice = false
                }
            } else {
                triggerAtMillis = WorkCalculator.nextBoundary(now, settings)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                wakeDevice = true
            }

            val canScheduleExact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            try {
                when {
                    canScheduleExact && wakeDevice ->
                        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    canScheduleExact ->
                        alarm.setExact(AlarmManager.RTC, triggerAtMillis, pendingIntent)
                    else ->
                        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (_: SecurityException) {
                // 未授予精确闹钟权限时的兜底
                alarm.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }

        private fun refreshPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WorkEndWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * 安排秒级服务看门狗闹钟：
         * - 服务未运行时先每 2 秒踢一次（最多 3 次）——刚添加卡片或阶段切换后
         *   让服务尽快上线；仍失败则退避为每分钟一次；
         * - 服务运行中每分钟自检一次，服务被系统/厂商省电策略杀死后自动复活。
         * 用非唤醒型 RTC 闹钟：息屏期间不触发不耗电，亮屏后立即补齐。
         */
        private fun scheduleWatchdog(context: Context, settings: WorkSettings, attempt: Int) {
            if (!needTick(context, settings)) return
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val delayMillis = when {
                WidgetTickService.isRunning -> 60_000L
                attempt < 3 -> 2_000L
                else -> 60_000L
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                Intent(context, WorkEndWidgetProvider::class.java)
                    .setAction(ACTION_TICK_WATCHDOG)
                    .putExtra(EXTRA_WATCHDOG_ATTEMPT, attempt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerAtMillis = System.currentTimeMillis() + delayMillis
            val canScheduleExact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            try {
                if (canScheduleExact) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, triggerAtMillis, pendingIntent)
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC, triggerAtMillis, pendingIntent)
                }
            } catch (_: SecurityException) {
                alarm.set(AlarmManager.RTC, triggerAtMillis, pendingIntent)
            }
        }

        private fun cancelWatchdog(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(
                PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_REQUEST_CODE,
                    Intent(context, WorkEndWidgetProvider::class.java)
                        .setAction(ACTION_TICK_WATCHDOG),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        private fun daysText(days: Int): String = if (days == 0) "今天" else "$days 天"

        private fun money(value: Double): String =
            "¥" + String.format(Locale.CHINA, "%,.2f", value)
    }
}
