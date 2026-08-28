package com.elemensha.copy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elemensha.copy.CopyUiState
import com.elemensha.copy.CopyViewModel

/**
 * 카피 설정 화면.
 *
 * [사용자 요청] 주문 크기 방식을 앱에서 고를 수 있어야 한다.
 * 세 방식이 서로 배타적이라 고른 방식의 입력칸만 보여주고,
 * 지금 설정이 실제로 얼마짜리 주문이 되는지 예시를 함께 적는다.
 */
@Composable
fun SettingsScreen(vm: CopyViewModel, state: CopyUiState) {
    val draft = state.draft
    val meta = state.meta

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // ================================================== 주문 크기 방식
        SectionCard(
            title = "주문 크기",
            subtitle = "리더가 매수할 때 내가 얼마나 살지 정하는 방식입니다.",
        ) {
            meta.sizingModes.forEach { mode ->
                val selected = draft.sizingMode == mode.value
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.Top,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { vm.editDraft { it.copy(sizingMode = mode.value) } },
                        )
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(mode.label, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (draft.sizingMode) {
                "equity" -> {
                    DecimalField(
                        label = "비율 보정",
                        value = draft.equityScale,
                        onChange = { vm.editDraft { d -> d.copy(equityScale = it) } },
                        suffix = "x",
                        supporting = "1 = 자산 비례 그대로. 0.5 = 그 절반만 따라감.",
                    )
                }
                "multiplier" -> {
                    DecimalField(
                        label = "배수",
                        value = draft.multiplier,
                        onChange = { vm.editDraft { d -> d.copy(multiplier = it) } },
                        suffix = "x",
                        supporting = "리더가 0.01 BTC 사면 0.5 일 때 나는 0.005 BTC.",
                    )
                }
                "fixed" -> {
                    DecimalField(
                        label = "1회 매수 금액",
                        value = draft.fixedNotional,
                        onChange = { vm.editDraft { d -> d.copy(fixedNotional = it) } },
                        suffix = "USDT",
                        supporting = "리더가 몇 번을 사든 나는 매번 이 금액.",
                    )
                }
            }
        }

        // ====================================================== 안전장치
        SectionCard(
            title = "안전장치",
            subtitle = "예상보다 큰 주문이 나가는 것을 막습니다.",
        ) {
            OptionalDecimalField(
                label = "배율 상한",
                value = draft.maxRatio,
                onChange = { vm.editDraft { d -> d.copy(maxRatio = it) } },
                suffix = "x",
                supporting = "비워두면 상한 없음. 리더 수량 대비 이 배율을 넘지 않습니다.",
            )
            Spacer(Modifier.height(10.dp))
            OptionalDecimalField(
                label = "종목별 포지션 상한",
                value = draft.maxPositionNotional,
                onChange = { vm.editDraft { d -> d.copy(maxPositionNotional = it) } },
                suffix = "USDT",
                supporting = "비워두면 상한 없음. 이 금액에 닿으면 추가 매수를 멈춥니다.",
            )
            Spacer(Modifier.height(12.dp))

            DropdownField(
                label = "최소 주문금액에 못 미칠 때",
                options = meta.belowMinimumModes,
                selected = meta.belowMinimumModes.find { it.value == draft.belowMinimum },
                optionLabel = { it.label },
                onSelect = { vm.editDraft { d -> d.copy(belowMinimum = it.value) } },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (draft.belowMinimum == "skip")
                    "내 자산이 작으면 BTC 처럼 최소 주문액이 큰 종목은 자주 건너뜁니다. " +
                    "건너뛴 신호는 로그와 [계좌] 화면에 남습니다."
                else
                    "주의: 최소 금액까지 올려서 삽니다. 자산이 작을수록 " +
                    "의도한 배율보다 훨씬 큰 주문이 나갈 수 있습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = if (draft.belowMinimum == "skip")
                    MaterialTheme.colorScheme.onSurfaceVariant else WarnAmber,
            )
        }

        // ==================================================== 거래소 설정
        SectionCard(
            title = "거래소 설정",
            subtitle = "카피 시작 후 첫 매수 때 바이낸스에 적용하고 되읽어 검증합니다.",
        ) {
            NumberField(
                label = "레버리지",
                value = draft.leverage.toString(),
                onValueChange = { raw ->
                    vm.editDraft { d -> d.copy(leverage = raw.toIntOrNull() ?: d.leverage) }
                },
                suffix = "x",
                decimal = false,
            )
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "마진 모드",
                options = meta.marginModes,
                selected = meta.marginModes.find {
                    it.value == draft.marginMode ||
                        (draft.marginMode == "CROSS" && it.value == "CROSSED")
                },
                optionLabel = { it.label },
                onSelect = { vm.editDraft { d -> d.copy(marginMode = it.value) } },
            )
        }

        // ======================================================== 청산
        SectionCard(
            title = "익절",
            subtitle = "내 평단 기준으로 내 지정가를 겁니다. 리더의 익절가를 " +
                       "베끼지 않습니다 — 체결가가 달라 평단이 어긋나기 때문입니다.",
        ) {
            var followLeader by remember(draft.takeProfitPercent) {
                mutableStateOf(draft.takeProfitPercent == null)
            }
            LabeledSwitch(
                title = "리더 익절률 따라가기",
                subtitle = "리더가 익절률을 바꾸면 나도 함께 바뀝니다.",
                checked = followLeader,
                onCheckedChange = { on ->
                    followLeader = on
                    vm.editDraft { d ->
                        d.copy(takeProfitPercent = if (on) null else 0.01)
                    }
                },
            )
            if (!followLeader) {
                Spacer(Modifier.height(8.dp))
                DecimalField(
                    label = "내 익절률",
                    value = (draft.takeProfitPercent ?: 0.01) * 100.0,
                    onChange = {
                        vm.editDraft { d -> d.copy(takeProfitPercent = it / 100.0) }
                    },
                    suffix = "%",
                    supporting = "평단 대비. 1 이면 평단 +1% 에 전량 매도.",
                )
            }
        }

        // ======================================================== 종목
        SectionCard(
            title = "따라갈 종목",
            subtitle = if (draft.symbols.isEmpty())
                "선택 없음 = 리더가 돌리는 전 종목을 따라갑니다."
            else "${draft.symbols.size}개 선택됨",
        ) {
            if (state.status.leaderSymbols.isEmpty()) {
                Text(
                    "리더가 아직 봇을 돌리지 않고 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.status.leaderSymbols.forEach { leader ->
                    val checked = leader.symbol in draft.symbols
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(leader.symbol, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(if (leader.running) "실행 중" else "정지됨")
                                    if (leader.hasPosition) append(" · 리더 보유 중")
                                    if (leader.timeframes.isNotEmpty()) {
                                        append(" · ${leader.timeframes.joinToString(",")}")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                vm.editDraft { d ->
                                    d.copy(
                                        symbols = if (checked) d.symbols - leader.symbol
                                                  else d.symbols + leader.symbol,
                                    )
                                }
                            },
                        )
                    }
                }
                if (draft.symbols.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.editDraft { it.copy(symbols = emptyList()) } }) {
                        Text("선택 해제 (전 종목 따라가기)")
                    }
                }
            }
        }

        // ======================================================== 실행
        SectionCard(title = "실행") {
            NumberField(
                label = "점검 주기",
                value = draft.pollSeconds.toString(),
                onValueChange = { raw ->
                    vm.editDraft { d -> d.copy(pollSeconds = raw.toIntOrNull() ?: d.pollSeconds) }
                },
                suffix = "초",
                supporting = "매수는 리더 신호에 즉시 반응합니다. 이 주기는 " +
                             "익절 주문이 제자리에 있는지 확인하는 간격입니다.",
                decimal = false,
            )
            Spacer(Modifier.height(6.dp))
            LabeledSwitch(
                title = "모의 실행",
                subtitle = "실제 주문 없이 계산 결과만 로그에 남깁니다. 먼저 이걸로 " +
                           "며칠 돌려보고 금액이 맞는지 확인하는 걸 권합니다.",
                checked = draft.dryRun,
                onCheckedChange = { vm.editDraft { d -> d.copy(dryRun = it) } },
            )
        }

        // ======================================================== 저장
        Column(Modifier.padding(12.dp)) {
            Button(
                onClick = vm::saveConfig,
                enabled = !state.busy && state.draftDirty,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.draftDirty) "설정 저장" else "저장됨",
                     fontWeight = FontWeight.Bold)
            }
            if (state.draftDirty) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = vm::resetDraft, modifier = Modifier.fillMaxWidth()) {
                    Text("변경 취소")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "저장하면 실행 중인 카피가 잠깐 멈췄다 새 설정으로 다시 시작됩니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))

            if (state.running) {
                OutlinedButton(
                    onClick = vm::stop,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("카피 정지")
                }
            } else {
                Button(
                    onClick = vm::start,
                    enabled = !state.busy && state.credentialsConfigured,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("카피 시작")
                }
                if (!state.credentialsConfigured) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "먼저 [더보기 > API 키]에서 내 바이낸스 키를 등록하세요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------- 입력칸

/**
 * 소수 입력칸.
 *
 * 사용자가 "0." 이나 빈 문자열을 지나가는 중간 상태를 허용해야 하므로
 * 화면용 문자열을 따로 들고 있다가 파싱에 성공할 때만 설정에 반영한다.
 */
@Composable
private fun DecimalField(
    label: String,
    value: Double,
    onChange: (Double) -> Unit,
    suffix: String? = null,
    supporting: String? = null,
) {
    var text by remember(value) { mutableStateOf(trimNumber(value)) }
    NumberField(
        label = label,
        value = text,
        onValueChange = {
            text = it
            it.toDoubleOrNull()?.let(onChange)
        },
        suffix = suffix,
        supporting = supporting,
    )
}

/** 비워두면 '설정 없음(null)' 이 되는 소수 입력칸. */
@Composable
private fun OptionalDecimalField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
    suffix: String? = null,
    supporting: String? = null,
) {
    var text by remember(value) { mutableStateOf(value?.let { trimNumber(it) } ?: "") }
    NumberField(
        label = label,
        value = text,
        onValueChange = { raw ->
            text = raw
            // 빈칸 = 상한 없음. 입력 중인 어중간한 값("0.")은 무시하고 넘어간다.
            if (raw.isBlank()) onChange(null) else raw.toDoubleOrNull()?.let(onChange)
        },
        suffix = suffix,
        supporting = supporting,
    )
}

/** 1.0 -> "1", 0.5 -> "0.5". 정수는 소수점을 붙이지 않는다. */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else value.toString()
