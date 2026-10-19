package com.example.workend.data

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 全局配置：工作班次、午休、每周工作日、薪资、计薪天数、发薪日。
 */
data class WorkSettings(
    val workStart: LocalTime = LocalTime.of(9, 0),
    val workEnd: LocalTime = LocalTime.of(18, 0),
    val lunchStart: LocalTime = LocalTime.of(12, 0),
    val lunchEnd: LocalTime = LocalTime.of(13, 30),
    val workDays: Set<DayOfWeek> = DEFAULT_WORK_DAYS,
    /** 月薪（元） */
    val monthlySalary: Double = 10_000.0,
    /** 每月计薪天数，法定标准为 (365 - 104) / 12 = 21.75 天 */
    val paidDaysPerMonth: Double = 21.75,
    /** 发薪日：每月第几天（1~31，大月不足时自动取当月最后一天） */
    val payday: Int = 15,
    /** 桌面卡片薪资秒级跳动（前台服务驱动，耗电略增） */
    val secondsTick: Boolean = false,
) {
    /** 当日薪资 = 月薪 / 计薪天数 */
    val dailySalary: Double
        get() = if (paidDaysPerMonth > 0.0) monthlySalary / paidDaysPerMonth else 0.0

    companion object {
        val DEFAULT_WORK_DAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
    }
}
