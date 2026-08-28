package com.elemensha.copy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elemensha.copy.CopyViewModel
import com.elemensha.copy.CopyUiState
import com.elemensha.copy.data.BalancePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val PERIODS = listOf(
    "day" to "일별",
    "week" to "주별",
    "month" to "월별",
    "quarter" to "분기",
    "year" to "연간",
)

@Composable
fun ChartScreen(vm: CopyViewModel, state: CopyUiState) {
    val history = state.balanceHistory
    var selected by remember { mutableStateOf<BalancePoint?>(null) }

    LaunchedEffect(Unit) { vm.loadBalanceHistory() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // ------------------------------------------------------ 기간 선택
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PERIODS.forEach { (value, label) ->
                FilterChip(
                    selected = state.balancePeriod == value,
                    onClick = { selected = null; vm.loadBalanceHistory(value) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (history == null) {
            SectionCard(title = "불러오는 중") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            return@Column
        }

        val points = history.points
        val summary = history.summary

        // ------------------------------------------------------ 요약 카드
        SectionCard(
            title = "내 순자산",
            subtitle = history.label,
        ) {
            val end = summary.endEquity
            val change = summary.change ?: 0.0
            val pct = summary.changePercent ?: 0.0
            val up = change >= 0

            Text(
                (end ?: 0.0).asUsd(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${if (up) "▲" else "▼"} ${abs(change).asUsd()}  (${"%+.2f".format(pct)}%)",
                color = if (up) ProfitGreen else LossRed,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(14.dp))
            EquityChart(
                points = points,
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )

            selected?.let { p ->
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(formatTs(p.ts, history.bucketSeconds),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatRow("순자산", p.equity.asUsd())
                StatRow("지갑 잔고", p.wallet.asUsd())
                StatRow(
                    "미실현 손익",
                    (if (p.unrealizedPnl >= 0) "+" else "") + p.unrealizedPnl.asUsd(),
                    if (p.unrealizedPnl >= 0) ProfitGreen else LossRed,
                )
                if (p.positionNotional > 0) {
                    StatRow("포지션 규모", p.positionNotional.asUsd())
                }
            }
        }

        // ------------------------------------------------------ 현재 상태
        SectionCard(
            title = "현재",
            subtitle = "지갑 잔고와 순자산의 차이가 지금 물려 있는 금액입니다.",
        ) {
            StatRow("지갑 잔고", (summary.wallet ?: 0.0).asUsd())
            val pnl = summary.unrealizedPnl ?: 0.0
            StatRow(
                "미실현 손익",
                (if (pnl >= 0) "+" else "") + pnl.asUsd(),
                if (pnl >= 0) ProfitGreen else LossRed,
            )
            StatRow("순자산", (summary.endEquity ?: 0.0).asUsd())
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            StatRow("기간 최고", (summary.maxEquity ?: 0.0).asUsd())
            StatRow("기간 최저", (summary.minEquity ?: 0.0).asUsd())
            summary.positionNotional?.let {
                if (it > 0) StatRow("포지션 규모", it.asUsd())
            }
            summary.openPositions?.let {
                if (it > 0) StatRow("보유 종목", "${it}개")
            }
        }

        // ------------------------------------------------------ 기록 상태
        SectionCard(title = "기록") {
            val rec = history.recording
            StatRow("수집 간격", "${rec.intervalSeconds / 60}분")
            StatRow("누적 표본", "${rec.totalSamples}건")
            rec.firstTs?.let { StatRow("최초 기록", formatTs(it, 3600)) }
            if (points.size < 2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "그래프를 그리려면 최소 2개의 기록이 필요합니다. " +
                    "카피가 실행 중이면 서버가 5분마다 자동으로 쌓습니다. " +
                    "정지 중에는 아래 버튼으로 직접 찍을 수 있습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.snapshotNow() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("지금 기록하고 새로고침")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 순자산 곡선. 외부 차트 라이브러리 없이 Canvas 로 직접 그린다.
 * (라이브러리를 넣으면 APK 가 2.2MB 에서 크게 불어난다)
 *
 * - 회색 점선: 지갑 잔고
 * - 굵은 선 + 그라데이션: 순자산 (잔고 + 미실현손익)
 * 둘이 벌어진 간격이 곧 물려 있는 금액이다.
 */
@Composable
private fun EquityChart(
    points: List<BalancePoint>,
    selected: BalancePoint?,
    onSelect: (BalancePoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("기록이 쌓이면 그래프가 표시됩니다",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val equityColor = if ((points.last().equity - points.first().equity) >= 0)
        ProfitGreen else LossRed
    val walletColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    // 지갑·순자산을 한 축에 그리므로 둘의 범위를 합쳐 계산한다
    val lo = points.minOf { min(it.equity, it.wallet) }
    val hi = points.maxOf { max(it.equity, it.wallet) }
    val pad = ((hi - lo) * 0.12).coerceAtLeast(hi * 0.002).coerceAtLeast(0.01)
    val minY = lo - pad
    val maxY = hi + pad
    val span = (maxY - minY).coerceAtLeast(1e-9)

    Canvas(
        modifier.pointerInput(points) {
            detectTapGestures { tap ->
                val idx = ((tap.x / size.width) * (points.size - 1))
                    .toInt().coerceIn(0, points.size - 1)
                onSelect(if (selected === points[idx]) null else points[idx])
            }
        }
    ) {
        val w = size.width
        val h = size.height
        fun yOf(v: Double) = (h - ((v - minY) / span * h)).toFloat()
        fun xOf(i: Int) = w * i / (points.size - 1).toFloat()

        // 가로 격자 4줄
        repeat(5) { i ->
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        // 지갑 잔고 (점선)
        val walletPath = Path().apply {
            points.forEachIndexed { i, p ->
                if (i == 0) moveTo(xOf(i), yOf(p.wallet)) else lineTo(xOf(i), yOf(p.wallet))
            }
        }
        drawPath(
            walletPath, walletColor,
            style = Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            ),
        )

        // 순자산 면적
        val areaPath = Path().apply {
            moveTo(xOf(0), h)
            points.forEachIndexed { i, p -> lineTo(xOf(i), yOf(p.equity)) }
            lineTo(xOf(points.size - 1), h)
            close()
        }
        drawPath(
            areaPath,
            Brush.verticalGradient(
                listOf(equityColor.copy(alpha = 0.28f), equityColor.copy(alpha = 0f)),
            ),
        )

        // 순자산 선
        val linePath = Path().apply {
            points.forEachIndexed { i, p ->
                if (i == 0) moveTo(xOf(i), yOf(p.equity)) else lineTo(xOf(i), yOf(p.equity))
            }
        }
        drawPath(linePath, equityColor, style = Stroke(width = 3.5f))

        // 선택 지점 표시
        selected?.let { sel ->
            val i = points.indexOfFirst { it.ts == sel.ts }
            if (i >= 0) {
                val x = xOf(i)
                drawLine(walletColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
                drawCircle(equityColor, radius = 7f, center = Offset(x, yOf(sel.equity)))
                drawCircle(Color.Black, radius = 3.5f, center = Offset(x, yOf(sel.equity)))
            }
        }

        // 마지막 지점 강조
        drawCircle(equityColor, radius = 5f,
                   center = Offset(xOf(points.size - 1), yOf(points.last().equity)))
    }

    // 범례 + Y축 양끝값
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("최저 ${points.minOf { it.equity }.asUsd()}",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("─ 순자산   ┈ 지갑",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("최고 ${points.maxOf { it.equity }.asUsd()}",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(formatTs(points.first().ts, 86400),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTs(points.last().ts, 86400),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTs(ts: Double, bucketSeconds: Int): String {
    val pattern = if (bucketSeconds < 86400) "MM-dd HH:mm" else "yyyy-MM-dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date((ts * 1000).toLong()))
}
