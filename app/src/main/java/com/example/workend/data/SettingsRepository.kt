package com.example.workend.data

import android.content.Context
import android.content.SharedPreferences
import com.example.workend.widget.WorkEndWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 基于 SharedPreferences 的配置仓库。
 * 通过 [StateFlow] 暴露配置，UI 与之后的桌面小部件均可订阅。
 */
class SettingsRepository(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readPrefs())
    val settings: StateFlow<WorkSettings> = _settings.asStateFlow()

    /** 非订阅场景下直接读取当前配置 */
    val current: WorkSettings
        get() = _settings.value

    fun update(transform: (WorkSettings) -> WorkSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        writePrefs(next)
        // 配置变化后立即同步桌面卡片
        WorkEndWidgetProvider.refreshAll(appContext)
    }

    private fun readPrefs(): WorkSettings {
        val defaults = WorkSettings()
        return WorkSettings(
            workStart = prefs.getString(KEY_WORK_START, null).toTimeOrDefault(defaults.workStart),
            workEnd = prefs.getString(KEY_WORK_END, null).toTimeOrDefault(defaults.workEnd),
            lunchStart = prefs.getString(KEY_LUNCH_START, null).toTimeOrDefault(defaults.lunchStart),
            lunchEnd = prefs.getString(KEY_LUNCH_END, null).toTimeOrDefault(defaults.lunchEnd),
            workDays = prefs.getString(KEY_WORK_DAYS, null)
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
                ?.map { DayOfWeek.of(it) }
                ?.toSet()
                ?: defaults.workDays,
            monthlySalary = prefs.getString(KEY_MONTHLY_SALARY, null)
                ?.toDoubleOrNull() ?: defaults.monthlySalary,
            paidDaysPerMonth = prefs.getString(KEY_PAID_DAYS, null)
                ?.toDoubleOrNull() ?: defaults.paidDaysPerMonth,
            payday = prefs.getInt(KEY_PAYDAY, defaults.payday).coerceIn(1, 31),
            secondsTick = prefs.getBoolean(KEY_SECONDS_TICK, defaults.secondsTick),
        )
    }

    private fun writePrefs(settings: WorkSettings) {
        prefs.edit()
            .putString(KEY_WORK_START, settings.workStart.toString())
            .putString(KEY_WORK_END, settings.workEnd.toString())
            .putString(KEY_LUNCH_START, settings.lunchStart.toString())
            .putString(KEY_LUNCH_END, settings.lunchEnd.toString())
            .putString(KEY_WORK_DAYS, settings.workDays.map { it.value }.sorted().joinToString(","))
            .putString(KEY_MONTHLY_SALARY, settings.monthlySalary.toString())
            .putString(KEY_PAID_DAYS, settings.paidDaysPerMonth.toString())
            .putInt(KEY_PAYDAY, settings.payday)
            .putBoolean(KEY_SECONDS_TICK, settings.secondsTick)
            .apply()
    }

    private fun String?.toTimeOrDefault(default: LocalTime): LocalTime =
        try {
            this?.let { LocalTime.parse(it) } ?: default
        } catch (_: Exception) {
            default
        }

    private companion object {
        const val PREFS_NAME = "work_end_settings"
        const val KEY_WORK_START = "work_start"
        const val KEY_WORK_END = "work_end"
        const val KEY_LUNCH_START = "lunch_start"
        const val KEY_LUNCH_END = "lunch_end"
        const val KEY_WORK_DAYS = "work_days"
        const val KEY_MONTHLY_SALARY = "monthly_salary"
        const val KEY_PAID_DAYS = "paid_days_per_month"
        const val KEY_PAYDAY = "payday"
        const val KEY_SECONDS_TICK = "seconds_tick"
    }
}
