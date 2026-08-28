package com.elemensha.copy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elemensha.copy.CopyUiState
import com.elemensha.copy.CopyViewModel
import com.elemensha.copy.data.Position
import com.elemensha.copy.data.SymbolCopyStatus

/**
 * 내 계좌 화면.
 *
 * [사용자 요청] 카피 앱도 본인 계정 자산이 어떤지 알 수 있어야 한다.
 * 맨 위에 순자산을 크게 놓고, 그 아래로 포지션과 종목별 카피 현황을 둔다.
 */
@Composable
fun AccountScreen(vm: CopyViewModel, state: CopyUiState) {
    var panicAsk by remember { mutableStateOf(false) }

    LaunchedEffect(state.credentialsConfigured) {
        if (state.credentialsConfigured) vm.loadAccount()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (!state.credentialsConfigured) {
            SectionCard(
                title = "API 키가 없습니다",
                subtitle = "[더보기 > API 키]에서 내 바이낸스 키를 등록해야 " +
                           "자산을 보고 카피를 시작할 수 있습니다.",
            ) {}
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        // ------------------------------------------------------- 내 자산
        val account = state.account
        val equity = account.equity ?: state.status.account.equity ?: 0.0
        val wallet = account.wallet ?: state.status.account.wallet ?: 0.0
        val pnl = account.unrealizedPnl ?: state.status.account.unrealizedPnl ?: 0.0

        SectionCard(
            title = "내 순자산",
            subtitle = "지갑 잔고 + 미실현손익. 손절이 없는 전략이라 이 값이 실제 성과입니다.",
        ) {
            Text(
                equity.asUsd(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            StatRow("지갑 잔고", wallet.asUsd())
            StatRow(
                "미실현 손익",
                (if (pnl >= 0) "+" else "") + pnl.asUsd(),
                if (pnl >= 0) ProfitGreen else LossRed,
            )
            account.available?.let { StatRow("주문 가능", it.asUsd()) }
            account.positionNotional?.let {
                if (it > 0) StatRow("포지션 규모", it.asUsd())
            }
            account.openPositions?.let { StatRow("보유 종목", "${it}개") }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { vm.loadAccount(); vm.loadOrders() },
                enabled = !state.accountLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.accountLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("거래소에서 다시 읽기")
            }
        }

        // ------------------------------------------------------- 카피 상태
        SectionCard(
            title = if (state.running) "카피 실행 중" else "카피 정지됨",
            subtitle = state.status.sizingLabel.ifBlank { "설정 탭에서 방식을 고르세요" },
        ) {
            state.status.lastError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = LossRed)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    OutlinedButton(
                        onClick = vm::stop,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("정지")
                    }
                } else {
                    Button(
                        onClick = vm::start,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("시작")
                    }
                }
                OutlinedButton(
                    onClick = { panicAsk = true },
                    enabled = !state.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
                    modifier = Modifier.weight(1f),
                ) { Text("긴급 청산") }
            }
        }

        // --------------------------------------------------------- 포지션
        if (state.positions.isEmpty()) {
            SectionCard(
                title = "보유 포지션 없음",
                subtitle = "리더가 매수하면 내 배율로 따라 삽니다.",
            ) {}
        } else {
            state.positions.forEach { PositionCard(it) }
        }

        // ------------------------------------------------ 종목별 카피 현황
        state.status.symbols.forEach { CopySymbolCard(it) }

        Spacer(Modifier.height(24.dp))
    }

    if (panicAsk) {
        AlertDialog(
            onDismissRequest = { panicAsk = false },
            icon = { Icon(Icons.Default.Warning, null, tint = LossRed) },
            title = { Text("긴급 청산") },
            text = {
                Text(
                    "카피를 정지하고, 내 미체결 주문을 전부 취소한 뒤 " +
                    "내 포지션을 시장가로 청산합니다. 손실이 확정될 수 있습니다.\n\n" +
                    "리더와 다른 팔로워의 계정은 영향받지 않습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.panic(); panicAsk = false }) {
                    Text("청산", color = LossRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { panicAsk = false }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun PositionCard(position: Position) {
    position.error?.let {
        SectionCard(title = position.symbol, subtitle = "조회 실패") {
            Text(it, style = MaterialTheme.typography.labelSmall, color = LossRed)
        }
        return
    }

    val pnl = position.unrealizedPnl
    val pnlPercent = if (position.entryPrice > 0 && position.size != 0.0) {
        (position.markPrice - position.entryPrice) / position.entryPrice * 100.0
    } else 0.0

    SectionCard(
        title = position.symbol,
        subtitle = "내 포지션 · ${position.leverage ?: 1}x " +
                   (position.marginMode ?: ""),
    ) {
        StatRow("수량", position.size.asPrice())
        StatRow("평단", "$" + position.entryPrice.asPrice())
        StatRow("현재가", "$" + position.markPrice.asPrice())
        StatRow("평가금액", position.notional.asUsd())
        StatRow(
            "미실현 손익",
            (if (pnl >= 0) "+" else "") + pnl.asUsd() +
                "  (%+.2f%%)".format(pnlPercent),
            if (pnl >= 0) ProfitGreen else LossRed,
        )
        if (position.liquidationPrice > 0) {
            StatRow("청산가", "$" + position.liquidationPrice.asPrice(), WarnAmber)
        }
        position.takeProfitPrice?.let {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            StatRow("익절 지정가", "$" + it.asPrice(), ProfitGreen)
            position.takeProfitAmount?.let { amount ->
                StatRow("익절 수량", amount.asPrice())
            }
        }
    }
}

@Composable
private fun CopySymbolCard(status: SymbolCopyStatus) {
    SectionCard(
        title = "${status.symbol} 카피 현황",
        subtitle = buildString {
            append(if (status.leaderRunning) "리더 실행 중" else "리더 정지됨")
            append(" · 익절 ${status.realizedTrades}회")
        },
    ) {
        StatRow("따라 산 횟수", "${status.mirroredBuys}회")
        if (status.skippedBuys > 0) {
            StatRow("건너뛴 신호", "${status.skippedBuys}회", WarnAmber)
        }
        status.lastRatio?.let { StatRow("최근 배율", "%.4g".format(it) + "x") }
        StatRow("익절률", (status.takeProfitPercent).asPercent())
        if (!status.settingsVerified) {
            StatRow("레버리지/마진", "미확인", WarnAmber)
        }

        status.lastSkipReason?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "마지막 건너뜀: $it",
                style = MaterialTheme.typography.labelSmall,
                color = WarnAmber,
            )
        }

        if (status.waitingAlone) {
            Spacer(Modifier.height(8.dp))
            Text(
                "리더는 이미 익절했지만 내 지정가는 아직 체결되지 않았습니다. " +
                "체결가 차이로 평단이 달라 생기는 정상적인 상황이며, " +
                "내 익절가에 닿으면 팔립니다.",
                style = MaterialTheme.typography.labelSmall,
                color = WarnAmber,
            )
        }

        status.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = LossRed)
        }
    }
}
