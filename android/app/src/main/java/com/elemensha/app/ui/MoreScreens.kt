package com.elemensha.app.ui

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
import com.elemensha.app.AppViewModel
import com.elemensha.app.UiState
import com.elemensha.app.update.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================================================================== 더보기

@Composable
fun MoreScreen(
    vm: AppViewModel,
    state: UiState,
    onOpenCredentials: () -> Unit,
    onOpenFollowers: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    var unpairAsk by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard(title = "서버", subtitle = vm.savedServerUrl) {
            StatRow("연결 상태", if (state.connected) "실시간 연결됨" else "끊김",
                    if (state.connected) ProfitGreen else LossRed)
            StatRow("서버 버전", state.serverVersion.ifBlank { "—" })
            StatRow("실행 중인 봇", "${state.bots.count { it.running }} / ${state.bots.size}")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = vm::refreshAll, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("새로고침")
            }
        }

        SectionCard(title = "설정") {
            ListItem(
                headlineContent = { Text("바이낸스 API 키") },
                supportingContent = {
                    Text(
                        if (state.credentials.configured)
                            state.credentials.apiKeyMasked +
                            (if (state.credentials.testnet) "  (테스트넷)" else "")
                        else "등록되지 않음",
                        color = if (state.credentials.configured) ProfitGreen else WarnAmber,
                    )
                },
                leadingContent = { Icon(Icons.Default.Key, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickableRow(onOpenCredentials),
            )
            ListItem(
                headlineContent = { Text("팔로워 관리") },
                supportingContent = {
                    Text(
                        if (state.followers.isEmpty()) "초대코드 발급"
                        else "${state.followers.size}명 · " +
                             "카피 중 ${state.followers.count { it.running }}명",
                    )
                },
                leadingContent = { Icon(Icons.Default.Group, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickableRow(onOpenFollowers),
            )
            ListItem(
                headlineContent = { Text("앱 업데이트") },
                supportingContent = { Text("현재 v${vm.updater.currentVersionName}") },
                leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickableRow(onOpenUpdate),
            )
        }

        SectionCard(
            title = "전략 고정 사항",
            subtitle = "이 항목들은 설계상 변경되지 않습니다.",
        ) {
            StatRow("방향", "롱 전용 (숏 없음)")
            StatRow("손절", "없음")
            StatRow("RSI 상단 청산", "없음")
            StatRow("청산 방식", "익절 전용")
            StatRow("진입 판정", "봉 완성(확정봉) 기준")
        }

        OutlinedButton(
            onClick = { unpairAsk = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) { Text("서버 연결 해제") }
        Spacer(Modifier.height(24.dp))
    }

    if (unpairAsk) {
        AlertDialog(
            onDismissRequest = { unpairAsk = false },
            title = { Text("연결 해제") },
            text = {
                Text("이 기기의 접속 토큰을 지웁니다. 서버의 봇은 계속 실행되며, " +
                     "다시 연결하려면 페어링 코드가 필요합니다.")
            },
            confirmButton = {
                TextButton(onClick = { vm.unpair(); unpairAsk = false }) {
                    Text("해제", color = LossRed)
                }
            },
            dismissButton = { TextButton(onClick = { unpairAsk = false }) { Text("취소") } },
        )
    }
}

// ================================================================= API 키 [7]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(vm: AppViewModel, state: UiState, onBack: () -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var testnet by remember { mutableStateOf(state.credentials.testnet) }
    var showSecret by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("바이낸스 API 키") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            if (state.credentials.configured) {
                SectionCard(title = "등록됨") {
                    StatRow("API 키", state.credentials.apiKeyMasked)
                    StatRow("모드", if (state.credentials.testnet) "테스트넷" else "실거래")
                    state.credentials.usdtBalance?.let { StatRow("USDT 잔고", it.asUsd()) }
                    state.credentials.canTrade?.let {
                        StatRow("거래 권한", if (it) "있음" else "없음",
                                if (it) ProfitGreen else LossRed)
                    }
                }
            }

            SectionCard(
                title = if (state.credentials.configured) "키 교체" else "키 등록",
                subtitle = "키는 서버에 암호화되어 저장되고, 앱에는 남지 않습니다.",
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
                    "· 가능하면 서버 공인 IP만 화이트리스트에 등록하세요.\n" +
                    "· 키를 파일이나 메신저에 저장하지 마세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.credentials.configured) {
                OutlinedButton(
                    onClick = { deleteAsk = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text("키 삭제 (전체 봇 정지)") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (deleteAsk) {
        AlertDialog(
            onDismissRequest = { deleteAsk = false },
            title = { Text("API 키 삭제") },
            text = { Text("모든 봇을 긴급 정지하고 키를 삭제합니다.") },
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

@Composable
fun LogScreen(state: UiState) {
    var levelFilter by remember { mutableStateOf<String?>(null) }
    val levels = listOf(null, "trade", "info", "warn", "error")
    val shown = remember(state.events, levelFilter) {
        if (levelFilter == null) state.events else state.events.filter { it.level == levelFilter }
    }

    Column(Modifier.fillMaxSize()) {
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
                Text("기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { event ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
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

// ================================================================ 인앱 업데이트

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(vm: AppViewModel, state: UiState, onBack: () -> Unit) {
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
                        OutlinedButton(onClick = vm::checkUpdate, modifier = Modifier.fillMaxWidth()) {
                            Text("다시 확인")
                        }
                    }
                }

                is Updater.State.Available -> {
                    SectionCard(
                        title = "새 버전 v${update.info.versionName}",
                        subtitle = update.info.apkSize?.let { "%.1f MB".format(it / 1048576.0) },
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
                        subtitle = "'알 수 없는 앱 설치'를 elemensha에 허용해 주세요. " +
                                   "이 앱은 스토어가 아닌 직접 설치 방식이라 1회 허용이 필요합니다.",
                    ) {
                        Button(
                            onClick = { vm.retryInstallAfterPermission(update) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("권한 설정 열기") }
                    }
                }

                is Updater.State.Failed -> {
                    SectionCard(title = "확인 실패", subtitle = update.message) {
                        OutlinedButton(onClick = vm::checkUpdate, modifier = Modifier.fillMaxWidth()) {
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

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    clickable(onClick = onClick)

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun formatTime(ts: Double): String = timeFormat.format(Date((ts * 1000).toLong()))

private fun levelColor(level: String): Color = when (level) {
    "trade" -> ProfitGreen
    "error" -> LossRed
    "warn" -> WarnAmber
    else -> Color(0xFF8A8A96)
}
