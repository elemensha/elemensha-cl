package com.elemensha.copy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elemensha.copy.CopyUiState
import com.elemensha.copy.CopyViewModel
import com.elemensha.copy.update.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================================================================== 더보기

@Composable
fun MoreScreen(
    vm: CopyViewModel,
    state: CopyUiState,
    onOpenCredentials: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    var leaveAsk by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard(title = "연결", subtitle = vm.savedServerUrl) {
            StatRow("연결 상태", if (state.connected) "실시간 연결됨" else "끊김",
                    if (state.connected) ProfitGreen else LossRed)
            StatRow("내 계정", vm.myLabel.ifBlank { state.me.label.ifBlank { "—" } })
            StatRow("카피", if (state.running) "실행 중" else "정지됨",
                    if (state.running) ProfitGreen else WarnAmber)
            StatRow("따라가는 종목", "${state.status.symbols.size}개")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = vm::refreshAll, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("새로고침")
            }
        }

        SectionCard(title = "설정") {
            ListItem(
                headlineContent = { Text("내 바이낸스 API 키") },
                supportingContent = {
                    Text(
                        if (state.credentialsConfigured)
                            state.me.credentials.apiKeyMasked +
                                (if (state.me.credentials.testnet) "  (테스트넷)" else "")
                        else "등록되지 않음",
                        color = if (state.credentialsConfigured) ProfitGreen else WarnAmber,
                    )
                },
                leadingContent = { Icon(Icons.Default.Key, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable(onClick = onOpenCredentials),
            )
            ListItem(
                headlineContent = { Text("로그") },
                supportingContent = { Text("내 카피 기록만 표시됩니다") },
                leadingContent = { Icon(Icons.Default.Article, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable(onClick = onOpenLogs),
            )
            ListItem(
                headlineContent = { Text("앱 업데이트") },
                supportingContent = { Text("현재 v${vm.updater.currentVersionName}") },
                leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable(onClick = onOpenUpdate),
            )
        }

        SectionCard(
            title = "카피 방식 고정 사항",
            subtitle = "이 항목들은 설계상 변경되지 않습니다.",
        ) {
            StatRow("진입", "리더 매수를 따라감")
            StatRow("청산", "내 평단 기준 내 지정가")
            StatRow("손절", "없음")
            StatRow("리더 청산 시", "내 지정가 유지 (강제 청산 안 함)")
            StatRow("방향", "롱 전용 (숏 없음)")
            Spacer(Modifier.height(8.dp))
            Text(
                "리더가 익절해도 내 포지션을 시장가로 밀어내지 않습니다. " +
                "체결가 차이로 평단이 조금씩 다르기 때문에, 강제로 맞추면 " +
                "손실이 확정될 수 있기 때문입니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "내 자산과 키는 내 것",
            subtitle = "리더가 볼 수 있는 범위",
        ) {
            Text(
                "· 리더는 내 API 키를 마스킹된 형태로만 봅니다.\n" +
                "· 리더는 내 잔고와 로그를 볼 수 없습니다.\n" +
                "· 리더는 내 카피를 정지시키거나 내 계정을 삭제할 수 있습니다. " +
                "다만 내 포지션을 대신 청산하지는 못합니다.\n" +
                "· 다른 팔로워는 내 어떤 정보도 볼 수 없습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = { leaveAsk = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) { Text("서버 연결 해제") }
        Spacer(Modifier.height(24.dp))
    }

    if (leaveAsk) {
        AlertDialog(
            onDismissRequest = { leaveAsk = false },
            title = { Text("연결 해제") },
            text = {
                Text(
                    "이 기기의 접속 토큰만 지웁니다. 서버의 카피 계정과 포지션은 " +
                    "그대로 남아 계속 동작합니다. 다시 연결하려면 리더에게 " +
                    "초대코드를 새로 받아야 합니다."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.leave(); leaveAsk = false }) {
                    Text("해제", color = LossRed)
                }
            },
            dismissButton = { TextButton(onClick = { leaveAsk = false }) { Text("취소") } },
        )
    }
}

// ================================================================== API 키

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(vm: CopyViewModel, state: CopyUiState, onBack: () -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var testnet by remember { mutableStateOf(state.me.credentials.testnet) }
    var showSecret by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("내 바이낸스 API 키") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            if (state.credentialsConfigured) {
                SectionCard(title = "등록됨") {
                    StatRow("API 키", state.me.credentials.apiKeyMasked)
                    StatRow("모드", if (state.me.credentials.testnet) "테스트넷" else "실거래")
                    state.me.credentials.usdtBalance?.let {
                        StatRow("USDT 잔고", it.asUsd())
                    }
                    state.me.credentials.canTrade?.let {
                        StatRow("거래 권한", if (it) "있음" else "없음",
                                if (it) ProfitGreen else LossRed)
                    }
                }
            }

            SectionCard(
                title = if (state.credentialsConfigured) "키 교체" else "키 등록",
                subtitle = "키는 서버에 암호화되어 저장되고, 앱에는 남지 않습니다. " +
                           "리더도 마스킹된 값만 볼 수 있습니다.",
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("API Key") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiSecret,
                    onValueChange = { apiSecret = it.trim() },
                    label = { Text("API Secret") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    visualTransformation =
                        if (showSecret) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                if (showSecret) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = "표시 전환",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                LabeledSwitch(
                    title = "테스트넷 사용",
                    subtitle = "실제 자금 없이 먼저 검증할 때 켜세요.",
                    checked = testnet,
                    onCheckedChange = { testnet = it },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.saveCredentials(apiKey, apiSecret, testnet) },
                    enabled = !state.busy && apiKey.length > 8 && apiSecret.length > 8,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("저장하고 연결 확인")
                }
            }

            SectionCard(
                title = "키 발급 시 주의",
                subtitle = "바이낸스 > API 관리에서 설정하세요.",
            ) {
                Text(
                    "· 출금 권한은 절대 켜지 마세요. 선물 거래 권한만 필요합니다.\n" +
                    "· 가능하면 서버 공인 IP만 화이트리스트에 등록하세요. " +
                    "주소는 리더에게 물어보면 됩니다.\n" +
                    "· 키를 파일이나 메신저에 저장하지 마세요.\n" +
                    "· 처음에는 테스트넷이나 [설정 > 모의 실행]으로 먼저 확인하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.credentialsConfigured) {
                OutlinedButton(
                    onClick = { deleteAsk = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text("키 삭제 (카피 정지)") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (deleteAsk) {
        AlertDialog(
            onDismissRequest = { deleteAsk = false },
            title = { Text("API 키 삭제") },
            text = {
                Text(
                    "카피를 정지하고 서버에서 내 키를 지웁니다. " +
                    "이미 열려 있는 포지션과 지정가 주문은 바이낸스에 그대로 남습니다 — " +
                    "필요하면 먼저 [계좌 > 긴급 청산]을 하세요."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteCredentials(); deleteAsk = false }) {
                    Text("삭제", color = LossRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = false }) { Text("취소") } },
        )
    }
}

// ==================================================================== 로그

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(state: CopyUiState, onBack: () -> Unit) {
    var levelFilter by remember { mutableStateOf<String?>(null) }
    val levels = listOf(null, "trade", "info", "warn", "error")
    val shown = remember(state.events, levelFilter) {
        if (levelFilter == null) state.events
        else state.events.filter { it.level == levelFilter }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("로그") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                levels.forEach { level ->
                    FilterChip(
                        selected = levelFilter == level,
                        onClick = { levelFilter = level },
                        label = { Text(level?.uppercase() ?: "전체") },
                    )
                }
            }
            HorizontalDivider()
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("기록이 없습니다.",
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { event ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    formatTime(event.ts),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    event.level.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = levelColor(event.level),
                                    fontWeight = FontWeight.Bold,
                                )
                                event.symbol?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                event.message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.SansSerif,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// ================================================================ 인앱 업데이트

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(vm: CopyViewModel, state: CopyUiState, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.checkUpdate() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("앱 업데이트") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionCard(title = "현재 버전") {
                StatRow("버전", "v${vm.updater.currentVersionName}")
                StatRow("빌드", vm.updater.currentVersionCode.toString())
            }

            when (val update = state.updateState) {
                is Updater.State.Idle, is Updater.State.Checking -> {
                    SectionCard(title = "최신 버전 확인 중") {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                is Updater.State.UpToDate -> {
                    SectionCard(
                        title = "최신 버전입니다",
                        subtitle = "새 버전이 나오면 여기에 표시됩니다.",
                    ) {
                        OutlinedButton(onClick = vm::checkUpdate,
                                       modifier = Modifier.fillMaxWidth()) {
                            Text("다시 확인")
                        }
                    }
                }

                is Updater.State.Available -> {
                    SectionCard(
                        title = "새 버전 v${update.info.versionName}",
                        subtitle = update.info.apkSize?.let {
                            "%.1f MB".format(it / 1048576.0)
                        },
                    ) {
                        if (update.info.notes.isNotBlank()) {
                            Text(update.info.notes,
                                 style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                        }
                        Button(
                            onClick = { vm.downloadUpdate(update.info) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("다운로드")
                        }
                    }
                }

                is Updater.State.Downloading -> {
                    SectionCard(title = "다운로드 중 ${update.percent}%") {
                        LinearProgressIndicator(
                            progress = { update.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is Updater.State.ReadyToInstall -> {
                    SectionCard(
                        title = "설치 준비 완료",
                        subtitle = "v${update.info.versionName} · 설치 화면이 열립니다.",
                    ) {
                        Button(
                            onClick = { vm.installUpdate(update) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("지금 설치") }
                    }
                }

                is Updater.State.NeedsPermission -> {
                    SectionCard(
                        title = "설치 권한이 필요합니다",
                        subtitle = "'알 수 없는 앱 설치'를 이 앱에 허용해 주세요. " +
                                   "스토어가 아닌 직접 설치 방식이라 1회 허용이 필요합니다.",
                    ) {
                        Button(
                            onClick = { vm.retryInstallAfterPermission(update) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("권한 설정 열기") }
                    }
                }

                is Updater.State.Failed -> {
                    SectionCard(title = "확인 실패", subtitle = update.message) {
                        OutlinedButton(onClick = vm::checkUpdate,
                                       modifier = Modifier.fillMaxWidth()) {
                            Text("다시 시도")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------- 유틸

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun formatTime(ts: Double): String = timeFormat.format(Date((ts * 1000).toLong()))

private fun levelColor(level: String): Color = when (level) {
    "trade" -> ProfitGreen
    "error" -> LossRed
    "warn" -> WarnAmber
    else -> Color(0xFF8A8A96)
}
