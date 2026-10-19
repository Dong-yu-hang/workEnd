package com.example.workend.logic

import com.example.workend.data.WorkSettings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.min

/**
 * 工作倒计时、发薪日、周末、当日薪资等计算。
 * 纯 Kotlin 实现，不依赖 Android，便于单测与桌面小部件复用。
 */
object WorkCalculator {

    /** 当前所处的时间段 */
    enum class Phase { BEFORE_WORK, MORNING, LUNCH, AFTERNOON, AFTER_WORK, REST_DAY }

    data class Countdown(
        val phase: Phase,
        /** 标签，如“距下班”“距上班”“午休中 · 距下午上班” */
        val label: String,
        /** 剩余时长的展示文本，如“3小时42分” */
        val remainingText: String,
    )

    /** 距离上下班的时间（自动考虑午休、每周工作日、已下班后的下一次上班） */
    fun workCountdown(now: LocalDateTime, settings: WorkSettings): Countdown {
        val isWorkday = now.dayOfWeek in settings.workDays
        val workStart = now.toLocalDate().atTime(settings.workStart)
        val workEnd = now.toLocalDate().atTime(settings.workEnd)
        val lunchStart = now.toLocalDate().atTime(settings.lunchStart)
        val lunchEnd = now.toLocalDate().atTime(settings.lunchEnd)

        return when {
            !isWorkday -> Countdown(
                Phase.REST_DAY,
                "休息中 · 距上班",
                formatRemaining(Duration.between(now, nextWorkStart(now, settings))),
            )
            now < workStart -> Countdown(
                Phase.BEFORE_WORK,
                "距上班",
                formatRemaining(Duration.between(now, workStart)),
            )
            now < lunchStart -> Countdown(
                Phase.MORNING,
                "距下班",
                formatRemaining(Duration.between(now, workEnd)),
            )
            now < lunchEnd -> Countdown(
                Phase.LUNCH,
                "午休中 · 距下午上班",
                formatRemaining(Duration.between(now, lunchEnd)),
            )
            now < workEnd -> Countdown(
                Phase.AFTERNOON,
                "距下班",
                formatRemaining(Duration.between(now, workEnd)),
            )
            else -> Countdown(
                Phase.AFTER_WORK,
                "已下班 · 距上班",
                formatRemaining(Duration.between(now, nextWorkStart(now, settings))),
            )
        }
    }

    /** 下一次上班时间（今天未到上班点则为今天，否则为下一个工作日） */
    fun nextWorkStart(from: LocalDateTime, settings: WorkSettings): LocalDateTime {
        if (settings.workDays.isEmpty()) return from // 未配置任何工作日，避免死循环
        var date = from.toLocalDate()
        if (date.dayOfWeek in settings.workDays && from.toLocalTime() < settings.workStart) {
            return date.atTime(settings.workStart)
        }
        date = date.plusDays(1)
        while (date.dayOfWeek !in settings.workDays) {
            date = date.plusDays(1)
        }
        return date.atTime(settings.workStart)
    }

    /**
     * 卡片内容将要变化的下一个时间点（上班/午休/下午/下班/零点/下一个工作日），
     * 用于调度刷新闹钟。
     */
    fun nextBoundary(now: LocalDateTime, settings: WorkSettings): LocalDateTime {
        val today = now.toLocalDate()
        val candidates = mutableListOf(
            today.plusDays(1).atStartOfDay(), // 跨天：发薪日、周末、工作日状态变化
            nextWorkStart(now, settings),
        )
        if (now.dayOfWeek in settings.workDays) {
            candidates += today.atTime(settings.workStart)
            candidates += today.atTime(settings.lunchStart)
            candidates += today.atTime(settings.lunchEnd)
            candidates += today.atTime(settings.workEnd)
        }
        return candidates
            .filter { it.isAfter(now) }
            .reduce { earliest, current -> if (current.isBefore(earliest)) current else earliest }
    }

    /** 较长剩余时长的紧凑文本：X天X小时 / X小时X分X秒 */
    fun formatRemainingLong(remaining: Duration): String =
        if (remaining.toDays() >= 1) {
            "${remaining.toDays()}天${remaining.toHours() % 24}小时"
        } else {
            formatRemaining(remaining)
        }

    /** 当日薪资行的状态说明（应用首页与桌面卡片共用） */
    fun salaryCaption(phase: Phase, settings: WorkSettings): String {
        val full = "全天 ¥" + String.format(Locale.CHINA, "%,.2f", settings.dailySalary)
        return when (phase) {
            Phase.BEFORE_WORK -> "${settings.workStart} 开始进账 · $full"
            Phase.LUNCH -> "午休暂停计薪 · $full"
            Phase.AFTER_WORK -> "已收工 · $full"
            Phase.REST_DAY -> "休息日不计薪 · $full"
            else -> full
        }
    }

    /** 距离周末（周六）的天数；周六、周日当天返回 0 */
    fun daysToWeekend(today: LocalDate): Int {
        val dayOfWeek = today.dayOfWeek.value // 周一=1 ... 周日=7
        return if (dayOfWeek >= 6) 0 else 6 - dayOfWeek
    }

    /** 距离发薪日的天数；发薪日当天返回 0。发薪日大于当月天数时取当月最后一天 */
    fun daysToPayday(today: LocalDate, payday: Int): Int {
        val effectiveThisMonth = effectivePayday(today, payday)
        return when {
            today.dayOfMonth < effectiveThisMonth ->
                effectiveThisMonth - today.dayOfMonth
            today.dayOfMonth == effectiveThisMonth -> 0
            else -> {
                val nextMonth = today.plusMonths(1)
                (today.lengthOfMonth() - today.dayOfMonth) + effectivePayday(nextMonth, payday)
            }
        }
    }

    private fun effectivePayday(month: LocalDate, payday: Int): Int =
        min(payday, month.lengthOfMonth())

    /**
     * 当日薪资实时累计：按工作进度逐秒增长。
     * 规则：只在每天的工作时间内累计，午休暂停；上班前为 0，下班后等于全天薪资。
     */
    fun todayEarned(now: LocalDateTime, settings: WorkSettings): Double {
        val daily = settings.dailySalary
        if (daily <= 0.0) return 0.0
        if (now.dayOfWeek !in settings.workDays) return 0.0

        val date = now.toLocalDate()
        val start = date.atTime(settings.workStart)
        val end = date.atTime(settings.workEnd)
        val lunchStart = date.atTime(settings.lunchStart)
        val lunchEnd = date.atTime(settings.lunchEnd)

        // 全天应工作秒数 = 上下班跨度 - 与午休的重叠部分
        val spanSeconds = Duration.between(start, end).seconds
        val lunchOverlap = overlapSeconds(start, end, lunchStart, lunchEnd)
        val workSeconds = spanSeconds - lunchOverlap
        if (workSeconds <= 0) return daily

        val until = minOf(now, end)
        if (until <= start) return 0.0
        val workedSeconds = Duration.between(start, until).seconds -
            overlapSeconds(start, until, lunchStart, lunchEnd)

        val progress = (workedSeconds.toDouble() / workSeconds).coerceIn(0.0, 1.0)
        return daily * progress
    }

    /** 两个时间段重叠的秒数 */
    private fun overlapSeconds(
        aStart: LocalDateTime,
        aEnd: LocalDateTime,
        bStart: LocalDateTime,
        bEnd: LocalDateTime,
    ): Long {
        val from = maxOf(aStart, bStart)
        val to = minOf(aEnd, bEnd)
        return if (to.isAfter(from)) Duration.between(from, to).seconds else 0
    }

    /** 剩余时长文本：X小时Y分Z秒 / Y分Z秒 / Z秒 */
    fun formatRemaining(remaining: Duration): String {
        if (remaining.isNegative || remaining.isZero) return "已到"
        val totalSeconds = remaining.seconds
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分${seconds}秒"
            minutes > 0 -> "${minutes}分${seconds}秒"
            else -> "${seconds}秒"
        }
    }

    /** 当日全天薪资（元） */
    fun todaySalary(settings: WorkSettings): Double = settings.dailySalary
}
