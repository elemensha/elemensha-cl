package com.elemensha.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elemensha.app.AppViewModel
import com.elemensha.app.UiState
import com.elemensha.app.data.SymbolInfo

@Composable
fun ConfigScreen(vm: AppViewModel, state: UiState) {
    val draft = state.draft
    var symbolPickerOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // ------------------------------------------------------------ 종목
        SectionCard(
            title = "종목",
            subtitle = "USDT 무기한 선물 ${state.symbols.size}종",
        ) {
            OutlinedButton(
                onClick = { symbolPickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(draft.symbol, fontWeight = FontWeight.SemiBold)
            }
            state.draftSymbolInfo?.let { info ->
                Spacer(Modifier.height(10.dp))
                StatRow("현재가", "$" + info.price.asPrice())
                StatRow(
                    "최소 주문액",
                    "$" + "%,.2f".format(info.effectiveMinNotional),
                    if (info.qtyBound) WarnAmber else null,
                )
                if (info.qtyBound) {
                    Text(
                        "이 종목은 최소 수량(${info.minQty})이 최소 금액을 결정합니다. " +
                        "\$10 단위 올림을 적용하면 주문이 한 단계 뛰므로 올리지 않습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                    )
                }
            }
        }

        // -------------------------------------------- 레버리지 / 마진 [요구사항 1]
        SectionCard(
            title = "레버리지 · 마진 모드",
            subtitle = "적용하면 바이낸스에 반영되고, 되읽어서 확인해 드립니다.",
        ) {
            NumberField(
                label = "레버리지",
                value = draft.leverage.toString(),
                onValueChange = { v ->
                    vm.editDraft { it.copy(leverage = v.toIntOrNull()?.coerceAtLeast(1) ?: 1) }
                },
                suffix = "x",
                decimal = false,
                supporting = state.exchangeSettings?.let { "최대 ${it.maxLeverage}x" },
            )
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "마진 모드",
                options = state.meta.marginModes,
                selected = state.meta.marginModes.find { it.value == draft.marginMode },
                optionLabel = { it.label },
                onSelect = { vm.editDraft { d -> d.copy(marginMode = it.value) } },
            )

            state.exchangeSettings?.let { current ->
                Spacer(Modifier.height(10.dp))
                StatRow("바이낸스 현재값",
                        "${current.leverage ?: "?"}x / ${current.marginMode ?: "?"}")
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = vm::applyExchangeSettings,
                enabled = !state.applyingSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.applyingSettings) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("바이낸스에 적용")
            }

            // 적용 결과 피드백
            state.settingsFeedback?.let { feedback ->
                Spacer(Modifier.height(10.dp))
                FeedbackRow(feedback.leverage.verified, feedback.leverage.message)
                FeedbackRow(feedback.marginMode.verified, feedback.marginMode.message)
            }
        }

        // ---------------------------------------------------- 타임프레임 [15종]
        SectionCard(
            title = "적용 봉",
            subtitle = "바이낸스 기본 15종. 선택한 봉마다 독립적으로 신호를 판정합니다.",
        ) {
            LabeledSwitch(
                title = "모든 봉에 적용",
                subtitle = "15종 전체를 한 번에 켜고 끕니다.",
                checked = draft.useAllTimeframes,
                onCheckedChange = vm::toggleAllTimeframes,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TimeframeChips(
                all = state.meta.allTimeframes,
                selected = draft.timeframes,
                enabled = !draft.useAllTimeframes,
                onToggle = vm::toggleTimeframe,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (draft.useAllTimeframes) "전체 15종 적용 중"
                else "${draft.timeframes.size}종 선택됨",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // -------------------------------------------------------------- RSI
        SectionCard(
            title = "RSI 진입 조건",
            subtitle = "판정은 항상 봉이 완성된 뒤(확정봉) 이뤄집니다.",
        ) {
            NumberField(
                label = "RSI 기간",
                value = draft.rsiPeriod.toString(),
                onValueChange = { v ->
                    vm.editDraft { it.copy(rsiPeriod = v.toIntOrNull()?.coerceIn(2, 100) ?: 14) }
                },
                decimal = false,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = "하한선",
                    value = draft.rsiLower.trimZeros(),
                    onValueChange = { v ->
                        vm.editDraft { it.copy(rsiLower = v.toDoubleOrNull() ?: it.rsiLower) }
                    },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "상한선",
                    value = draft.rsiUpper.trimZeros(),
                    onValueChange = { v ->
                        vm.editDraft { it.copy(rsiUpper = v.toDoubleOrNull() ?: it.rsiUpper) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "진입 시점",
                options = state.meta.entryTriggers,
                selected = state.meta.entryTriggers.find { it.value == draft.entryTrigger },
                optionLabel = { it.label },
                onSelect = { vm.editDraft { d -> d.copy(entryTrigger = it.value) } },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                triggerHint(draft.entryTrigger, draft.rsiLower, draft.rsiUpper),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------------- 매수
        SectionCard(
            title = "매수 금액",
            subtitle = "신호가 뜰 때마다 이 금액으로 시장가 매수합니다.",
        ) {
            NumberField(
                label = "지갑 비율",
                value = (draft.walletPercentage * 100).trimZeros(),
                onValueChange = { v ->
                    val pct = v.toDoubleOrNull()
                    if (pct != null) vm.editDraft { it.copy(walletPercentage = pct / 100.0) }
                },
                suffix = "%",
                supporting = "USDT 잔고 대비. 기본 0.1%",
            )
            Spacer(Modifier.height(10.dp))
            NumberField(
                label = "최소 주문액 올림 단위",
                value = draft.minNotionalRoundUp.trimZeros(),
                onValueChange = { v ->
                    vm.editDraft {
                        it.copy(minNotionalRoundUp = v.toDoubleOrNull() ?: it.minNotionalRoundUp)
                    }
                },
                suffix = "$",
                supporting = "0 이면 올림하지 않음",
            )
            Spacer(Modifier.height(10.dp))
            NumberField(
                label = "최대 매수 횟수 (봉별)",
                value = draft.maxAdditionalBuys?.toString() ?: "",
                onValueChange = { v ->
                    vm.editDraft { it.copy(maxAdditionalBuys = v.toIntOrNull()) }
                },
                decimal = false,
                supporting = "비워두면 무제한",
            )
        }

        // ------------------------------------------------------------- 청산
        SectionCard(
            title = "익절",
            subtitle = "손절 없음 · 숏 없음 · RSI 상단 청산 없음. 익절만 동작합니다.",
        ) {
            NumberField(
                label = "익절률",
                value = (draft.takeProfitPercent * 100).trimZeros(),
                onValueChange = { v ->
                    val pct = v.toDoubleOrNull()
                    if (pct != null) vm.editDraft { it.copy(takeProfitPercent = pct / 100.0) }
                },
                suffix = "%",
                supporting = "평단 대비. reduceOnly 지정가로 전량 청산",
            )
        }

        // ------------------------------------------------------------- 실행
        SectionCard(title = "실행") {
            NumberField(
                label = "확인 주기",
                value = draft.pollSeconds.toString(),
                onValueChange = { v ->
                    vm.editDraft { it.copy(pollSeconds = v.toIntOrNull()?.coerceIn(5, 600) ?: 20) }
                },
                suffix = "초",
                decimal = false,
            )
            Spacer(Modifier.height(6.dp))
            LabeledSwitch(
                title = "모의 매매",
                subtitle = "실제 주문을 넣지 않고 신호만 기록합니다.",
                checked = draft.dryRun,
                onCheckedChange = { on -> vm.editDraft { it.copy(dryRun = on) } },
            )
            NumberField(
                label = "총 포지션 상한 (선택)",
                value = draft.maxPositionNotional?.trimZeros() ?: "",
                onValueChange = { v ->
                    vm.editDraft { it.copy(maxPositionNotional = v.toDoubleOrNull()) }
                },
                suffix = "$",
                supporting = "비워두면 상한 없음",
            )
        }

        Button(
            onClick = vm::startBot,
            enabled = !state.busy && state.credentials.configured,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("이 설정으로 봇 시작", fontWeight = FontWeight.Bold)
        }
        if (!state.credentials.configured) {
            Text(
                "먼저 [더보기 > API 키]에서 바이낸스 키를 등록하세요.",
                style = MaterialTheme.typography.labelSmall,
                color = WarnAmber,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (symbolPickerOpen) {
        SymbolPickerDialog(
            symbols = state.symbols,
            loading = state.symbolsLoading,
            onPick = { vm.selectSymbol(it.symbol); symbolPickerOpen = false },
            onRefresh = { vm.loadSymbols(refresh = true) },
            onDismiss = { symbolPickerOpen = false },
        )
    }
}

@Composable
private fun FeedbackRow(verified: Boolean, message: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (verified) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (verified) ProfitGreen else LossRed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = if (verified) ProfitGreen else LossRed,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeframeChips(
    all: List<String>,
    selected: List<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        all.forEach { tf ->
            FilterChip(
                selected = tf in selected,
                onClick = { onToggle(tf) },
                enabled = enabled,
                label = { Text(tf) },
            )
        }
    }
}

@Composable
private fun SymbolPickerDialog(
    symbols: List<SymbolInfo>,
    loading: Boolean,
    onPick: (SymbolInfo) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, symbols) {
        if (query.isBlank()) symbols
        else symbols.filter { it.base.contains(query, true) || it.symbol.contains(query, true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("종목 선택 (${symbols.size})") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("검색") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(filtered, key = { it.symbol }) { info ->
                        ListItem(
                            headlineContent = {
                                Text(info.base, fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text(
                                    "최소 $" + "%,.2f".format(info.effectiveMinNotional) +
                                    "  ·  최대 ${info.maxLeverage}x",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            trailingContent = {
                                Text("$" + info.price.asPrice(),
                                     style = MaterialTheme.typography.bodySmall)
                            },
                            modifier = Modifier.clickable { onPick(info) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text("목록 새로고침") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun triggerHint(trigger: String, lower: Double, upper: Double): String = when (trigger) {
    "cross_up_lower" -> "직전 확정봉 RSI < ${lower.trimZeros()} 이고 현재 확정봉 RSI ≥ ${lower.trimZeros()} 일 때 매수"
    "cross_down_lower" -> "직전 확정봉 RSI ≥ ${lower.trimZeros()} 이고 현재 확정봉 RSI < ${lower.trimZeros()} 일 때 매수"
    "below_lower" -> "확정봉 RSI 가 ${lower.trimZeros()} 아래인 동안 봉마다 매수"
    "cross_up_upper" -> "직전 확정봉 RSI < ${upper.trimZeros()} 이고 현재 확정봉 RSI ≥ ${upper.trimZeros()} 일 때 매수"
    "cross_down_upper" -> "직전 확정봉 RSI ≥ ${upper.trimZeros()} 이고 현재 확정봉 RSI < ${upper.trimZeros()} 일 때 매수"
    "above_upper" -> "확정봉 RSI 가 ${upper.trimZeros()} 위인 동안 봉마다 매수"
    else -> ""
}

/** 30.0 -> "30",  0.1 -> "0.1" */
fun Double.trimZeros(): String =
    if (this == kotlin.math.floor(this) && kotlin.math.abs(this) < 1e12)
        this.toLong().toString()
    else this.toString().trimEnd('0').trimEnd('.')
