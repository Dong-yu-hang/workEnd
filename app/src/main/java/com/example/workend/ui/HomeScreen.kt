package com.example.workend.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.workend.R
import com.example.workend.data.SettingsRepository
import com.example.workend.data.WorkSettings
import com.example.workend.logic.WorkCalculator
import com.example.workend.logic.WorkCalculator.Phase
import com.example.workend.ui.components.TimeField
import com.example.workend.widget.WorkEndWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun HomeScreen(repository: SettingsRepository) {
    val settings by repository.settings.collectAsState()
    // 每秒刷新，并对齐到整秒边界，避免计时显示漂移
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 状态栏/手势条避让：放在 verticalScroll 之前，
            // 让整个滚动区域落在系统栏内侧，标题不会滑到状态栏底下
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "下班倒数",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        WidgetPreviewCard(settings = settings, now = now)

        // ── 桌面卡片开关 ─────────────────────────────────────
        SectionCard(title = "桌面卡片") {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var placed by remember { mutableStateOf(WorkEndWidgetProvider.isPlaced(context)) }

            // 每次回到前台都重新核对卡片是否真的在桌面上：
            // 用户可能在桌面手动移除/添加过卡片，或刚从系统「添加卡片」对话框返回。
            // 若不重同步，开关会卡在旧值上——明明没加却显示"已在桌面"，
            // 导致点击永远走不到发起添加请求的分支（弹窗出不来）。
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        placed = WorkEndWidgetProvider.isPlaced(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "在桌面显示卡片",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (placed) "卡片已在桌面，长按卡片可移除或调整大小"
                        else "添加后不上应用也能看倒计时和薪资进度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = placed,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            scope.launch {
                                val requested = WorkEndWidgetProvider.requestPin(context)
                                if (!requested) {
                                    Toast.makeText(
                                        context,
                                        "当前桌面不支持快捷添加，请长按桌面空白处 → 小工具手动添加",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    return@launch
                                }
                                // 等待用户在系统对话框中确认（最长 6 秒）
                                repeat(12) {
                                    delay(500)
                                    if (WorkEndWidgetProvider.isPlaced(context)) {
                                        placed = true
                                        return@launch
                                    }
                                }
                                // 用户取消或桌面未响应：按桌面实际状态回落并提示
                                placed = WorkEndWidgetProvider.isPlaced(context)
                                if (!placed) {
                                    Toast.makeText(
                                        context,
                                        "未完成添加：可再点一次开关重试，或长按桌面空白处 → 小工具手动添加",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        } else {
                            // Android 不允许应用移除已放置的卡片
                            Toast.makeText(
                                context,
                                "Android 不允许应用自动移除卡片，请长按桌面上的卡片手动移除",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "薪资秒级跳动",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (settings.secondsTick) "已开启：工作时段每秒刷新，通知栏有常驻提示"
                        else "关闭中：桌面薪资每分钟自动增长（更省电）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.secondsTick,
                    enabled = placed,
                    onCheckedChange = { wantOn ->
                        repository.update { it.copy(secondsTick = wantOn) }
                    },
                )
            }
        }

        // ── 工作班次（上下班时间） ─────────────────────────────
        SectionCard(title = "工作班次") {
            TimeField(
                label = "上班时间",
                value = settings.workStart,
                onChange = { value -> repository.update { it.copy(workStart = value) } },
            )
            TimeField(
                label = "下班时间",
                value = settings.workEnd,
                onChange = { value -> repository.update { it.copy(workEnd = value) } },
            )
        }

        // ── 午休时间 ─────────────────────────────────────────
        SectionCard(title = "午休时间") {
            TimeField(
                label = "午休开始",
                value = settings.lunchStart,
                onChange = { value -> repository.update { it.copy(lunchStart = value) } },
            )
            TimeField(
                label = "午休结束",
                value = settings.lunchEnd,
                onChange = { value -> repository.update { it.copy(lunchEnd = value) } },
            )
            val lunchMinutes = Duration.between(settings.lunchStart, settings.lunchEnd).toMinutes()
            if (lunchMinutes > 0) {
                Text(
                    text = "午休时长 ${lunchMinutes / 60}小时${lunchMinutes % 60}分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 每周有哪几天工作 ──────────────────────────────────
        SectionCard(title = "每周工作日") {
            WorkDayChips(
                selected = settings.workDays,
                onToggle = { day ->
                    repository.update {
                        if (day in it.workDays) it.copy(workDays = it.workDays - day)
                        else it.copy(workDays = it.workDays + day)
                    }
                },
            )
        }

        // ── 月薪 / 计薪天数 / 当日薪资 ────────────────────────
        SectionCard(title = "薪资设置") {
            var salaryText by remember {
                mutableStateOf(settings.monthlySalary.toInputText())
            }
            var paidDaysText by remember {
                mutableStateOf(settings.paidDaysPerMonth.toInputText())
            }
            OutlinedTextField(
                value = salaryText,
                onValueChange = { text ->
                    salaryText = text
                    text.toDoubleOrNull()?.let { value ->
                        repository.update { it.copy(monthlySalary = value) }
                    }
                },
                label = { Text("月薪（元）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = paidDaysText,
                onValueChange = { text ->
                    paidDaysText = text
                    text.toDoubleOrNull()?.let { value ->
                        repository.update { it.copy(paidDaysPerMonth = value) }
                    }
                },
                label = { Text("每月计薪天数") },
                supportingText = { Text("法定标准 (365 − 104) ÷ 12 = 21.75 天") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "当日薪资 ≈ ${money(settings.dailySalary)}" +
                    "（${money(settings.monthlySalary)} ÷ ${settings.paidDaysPerMonth} 天）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        // ── 发薪日 ───────────────────────────────────────────
        SectionCard(title = "发薪日") {
            val payday = settings.payday
            val days = WorkCalculator.daysToPayday(LocalDate.now(), payday)
            Text(
                text = "每月 $payday 日 · " +
                    if (days == 0) "今天发薪日 🎉" else "距下次发薪还有 $days 天",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = payday.toFloat(),
                onValueChange = { value ->
                    repository.update { it.copy(payday = value.roundToInt().coerceIn(1, 31)) }
                },
                valueRange = 1f..31f,
                steps = 29,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 每周工作日选择，用 FlowRow 避免小屏放不下 7 个选项 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WorkDayChips(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(DAY_NAMES[day.value - 1]) },
            )
        }
    }
}

/**
 * 卡片效果展示：模拟桌面小部件（4 列 × 2 行）的最终外观。
 * 内容块（倒计时 + 数据行）靠左，右侧桌宠随阶段切换表情。
 */
@Composable
private fun WidgetPreviewCard(settings: WorkSettings, now: LocalDateTime) {
    val countdown = WorkCalculator.workCountdown(now, settings)
    val daysToPayday = WorkCalculator.daysToPayday(now.toLocalDate(), settings.payday)
    val daysToWeekend = WorkCalculator.daysToWeekend(now.toLocalDate())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF171E3C), Color(0xFF2E2350))),
            )
            .padding(20.dp),
    ) {
        // 第 1 行：标题 + 日期标签
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "上班日程",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = todayLabel(now, settings),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // 第 2~4 行：内容块整体靠左，右侧桌宠随阶段切换表情
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 第 2 行：阶段标签 + 倒计时（与桌面卡片同款横排）
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = countdown.label,
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = countdown.remainingText,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = Color.White.copy(alpha = 0.15f),
                )

                // 第 3 行：距发薪日 + 距周末 + 当日薪资（薪资列加宽避免换行）
                Row {
                    PreviewStat(
                        label = "距发薪日",
                        value = daysText(daysToPayday),
                        modifier = Modifier.weight(0.7f),
                    )
                    PreviewStat(
                        label = "距周末",
                        value = daysText(daysToWeekend),
                        modifier = Modifier.weight(0.7f),
                    )
                    PreviewStat(
                        label = "当日薪资",
                        value = money(WorkCalculator.todayEarned(now, settings)),
                        caption = WorkCalculator.salaryCaption(countdown.phase, settings),
                        valueColor = Color(0xFFFFD180),
                        modifier = Modifier.weight(1.6f),
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Image(
                painter = painterResource(
                    id = when (countdown.phase) {
                        Phase.MORNING, Phase.AFTERNOON -> R.drawable.pet_working
                        Phase.AFTER_WORK -> R.drawable.pet_off
                        else -> R.drawable.pet_rest
                    },
                ),
                contentDescription = "桌宠",
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Composable
private fun PreviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White,
    caption: String? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        caption?.let { text ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 通用模块卡片容器 */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

private fun todayLabel(now: LocalDateTime, settings: WorkSettings): String {
    val week = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
    val isWorkday = now.dayOfWeek in settings.workDays
    return "$week · " + if (isWorkday) "工作日" else "休息日"
}

private fun daysText(days: Int): String = if (days == 0) "今天" else "$days 天"

private fun money(value: Double): String =
    "¥" + String.format(Locale.CHINA, "%,.2f", value)

private fun Double.toInputText(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()
