package com.elemensha.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elemensha.app.AppViewModel
import com.elemensha.app.UiState
import com.elemensha.app.data.BotStatus

@Composable
fun DashboardScreen(vm: AppViewModel, state: UiState) {
    var panicTarget by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (!state.credentials.configured) {
            SectionCard(
                title = "API 키가 없습니다",
                subtitle = "[더보기 > API 키]에서 바이낸스 키를 등록하면 봇을 돌릴 수 있습니다.",
            ) {}
        }

        if (state.bots.isEmpty()) {
            SectionCard(
                title = "실행 중인 봇이 없습니다",
                subtitle = "[설정] 탭에서 파라미터를 정하고 봇을 시작하세요.",
            ) {}
        }

        state.bots.forEach { bot ->
            BotCard(
                bot = bot,
                onStop = { vm.stopBot(bot.symbol) },
                onPanic = { panicTarget = bot.symbol },
                onDelete = { vm.deleteBot(bot.symbol) },
                busy = state.busy,
            )
        }

        if (state.bots.any { it.running }) {
            OutlinedButton(
                onClick = { panicTarget = "__all__" },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Icon(Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("전체 긴급 청산")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    panicTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { panicTarget = null },
            icon = { Icon(Icons.Default.Warning, null, tint = LossRed) },
            title = { Text(if (target == "__all__") "전체 긴급 청산" else "$target 긴급 청산") },
            text = {
                Text(
                    "봇을 즉시 정지하고, 열린 주문을 전부 취소한 뒤 " +
                    "포지션을 시장가로 청산합니다. 손실이 확정될 수 있습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target == "__all__") vm.panicAll() else vm.panicBot(target)
                    panicTarget = null
                }) { Text("청산", color = LossRed) }
            },
            dismissButton = {
                TextButton(onClick = { panicTarget = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun BotCard(
    bot: BotStatus,
    onStop: () -> Unit,
    onPanic: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
) {
    val live = bot.live
    val pnl = live?.unrealizedPnl ?: 0.0

    SectionCard(
        title = bot.symbol,
        subtitle = buildString {
            append(if (bot.running) "실행 중" else "정지됨")
            if (bot.entryTriggerLabel.isNotBlank()) append(" · ${bot.entryTriggerLabel}")
            append(" · 익절 ${bot.realizedTrades}회")
        },
    ) {
        live?.let {
            StatRow("현재가", "$" + (it.price ?: 0.0).asPrice())
            StatRow("USDT 잔고", (it.balance ?: 0.0).asUsd())
            StatRow("포지션", (it.positionSize ?: 0.0).asPrice())
            if ((it.positionSize ?: 0.0) > 0.0) {
                StatRow("평단", "$" + (it.entryPrice ?: 0.0).asPrice())
                StatRow(
                    "미실현 손익",
                    (if (pnl >= 0) "+" else "") + pnl.asUsd(),
                    if (pnl >= 0) ProfitGreen else LossRed,
                )
                StatRow("청산가", "$" + (it.liquidationPrice ?: 0.0).asPrice(), WarnAmber)
            }
        }

        bot.takeProfit.price?.let { tp ->
            StatRow("익절 지정가", "$" + tp.asPrice(), ProfitGreen)
        }

        if (bot.timeframes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("봉별 RSI", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            bot.timeframes.forEach { (tf, status) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tf, style = MaterialTheme.typography.bodySmall,
                         modifier = Modifier.width(40.dp))
                    Text(
                        "${status.prevRsi.asRsi()} → ${status.rsi.asRsi()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = rsiColor(status.rsi),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "매수 ${status.buyCount}" + (status.maxBuys?.let { "/$it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                status.error?.let {
                    Text("  $it", style = MaterialTheme.typography.labelSmall, color = LossRed)
                }
            }
        }

        bot.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = LossRed)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bot.running) {
                OutlinedButton(onClick = onStop, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("정지")
                }
            } else {
                OutlinedButton(onClick = onDelete, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("삭제")
                }
            }
            OutlinedButton(
                onClick = onPanic,
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
                modifier = Modifier.weight(1f),
            ) { Text("긴급 청산") }
        }
    }
}

private fun rsiColor(rsi: Double?): Color = when {
    rsi == null -> Color.Gray
    rsi >= 70 -> LossRed
    rsi <= 30 -> ProfitGreen
    else -> Color(0xFFB0B0BB)
}
