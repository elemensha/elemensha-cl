package com.elemensha.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elemensha.app.AppViewModel
import com.elemensha.app.UiState
import com.elemensha.app.data.FollowerRow
import com.elemensha.app.data.Invite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 팔로워 관리 (리더 전용).
 *
 * 여기서 볼 수 있는 건 계정이 살아있는지와 키가 등록됐는지까지다.
 * 팔로워의 잔고·포지션·로그는 서버가 아예 내려주지 않는다.
 * 카피를 멈추거나 계정을 지울 수는 있지만, 남의 포지션을 대신
 * 청산하는 기능은 의도적으로 없다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowersScreen(vm: AppViewModel, state: UiState, onBack: () -> Unit) {
    var confirmStop by remember { mutableStateOf<FollowerRow?>(null) }
    var confirmDelete by remember { mutableStateOf<FollowerRow?>(null) }

    LaunchedEffect(Unit) { vm.loadFollowers() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("팔로워 관리") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "뒤로") }
            },
            actions = {
                IconButton(onClick = vm::loadFollowers) {
                    Icon(Icons.Default.Refresh, "새로고침")
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            state.newInvite?.let {
                NewInviteCard(it, vm.savedServerUrl, onDismiss = vm::dismissNewInvite)
            }

            InviteForm(vm, state)

            // ------------------------------------------------ 발급된 코드
            SectionCard(
                title = "발급된 초대코드",
                subtitle = if (state.invites.isEmpty()) "없음"
                           else "${state.invites.count { !it.exhausted }}개 사용 가능",
            ) {
                if (state.invites.isEmpty()) {
                    Text(
                        "위에서 코드를 발급해 팔로워에게 전달하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.invites.forEach { invite ->
                        InviteRow(invite, onDelete = { vm.deleteInvite(invite.code) },
                                  busy = state.busy)
                    }
                }
            }

            // -------------------------------------------------- 팔로워 목록
            SectionCard(
                title = "팔로워",
                subtitle = if (state.followers.isEmpty()) "아직 없습니다"
                           else "${state.followers.size}명 · " +
                                "카피 중 ${state.followers.count { it.running }}명",
            ) {
                if (state.followers.isEmpty()) {
                    Text(
                        "초대코드로 가입하면 여기에 나타납니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.followers.forEach { follower ->
                FollowerCard(
                    follower = follower,
                    busy = state.busy,
                    onStop = { confirmStop = follower },
                    onDelete = { confirmDelete = follower },
                )
            }

            SectionCard(
                title = "내가 할 수 있는 것과 없는 것",
                subtitle = "팔로워의 계좌는 팔로워의 것입니다.",
            ) {
                StatRow("팔로워 API 키", "마스킹된 값만 보임")
                StatRow("팔로워 잔고·포지션·로그", "볼 수 없음")
                StatRow("카피 정지", "가능")
                StatRow("계정 삭제", "가능")
                StatRow("팔로워 포지션 청산", "불가", WarnAmber)
                Spacer(Modifier.height(8.dp))
                Text(
                    "계정을 지워도 팔로워의 포지션과 지정가 주문은 바이낸스에 " +
                    "그대로 남습니다. 정리는 팔로워가 자기 앱에서 해야 합니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    confirmStop?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmStop = null },
            title = { Text("${target.label} 카피 정지") },
            text = {
                Text(
                    "이 팔로워의 카피를 멈춥니다. 새 신호를 더는 따라가지 않습니다.\n\n" +
                    "이미 열린 포지션과 익절 지정가는 그대로 남습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.stopFollower(target.id, target.label); confirmStop = null
                }) { Text("정지", color = WarnAmber) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = null }) { Text("취소") }
            },
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Default.Warning, null, tint = LossRed) },
            title = { Text("${target.label} 계정 삭제") },
            text = {
                Text(
                    "이 팔로워의 API 키·기기 토큰·로그·잔고 기록을 전부 지웁니다. " +
                    "되돌릴 수 없고, 다시 붙으려면 초대코드를 새로 받아야 합니다.\n\n" +
                    "바이낸스에 열려 있는 포지션과 주문은 지워지지 않습니다. " +
                    "먼저 팔로워에게 정리하도록 알리세요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFollower(target.id, target.label); confirmDelete = null
                }) { Text("삭제", color = LossRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("취소") }
            },
        )
    }
}

// -------------------------------------------------------------- 코드 발급

@Composable
private fun InviteForm(vm: AppViewModel, state: UiState) {
    var label by remember { mutableStateOf("") }
    var maxUses by remember { mutableStateOf("1") }
    var ttlHours by remember { mutableStateOf("72") }

    SectionCard(
        title = "초대코드 발급",
        subtitle = "코드를 받은 사람이 elemensha copy 앱에서 가입합니다.",
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("메모 (누구에게 줄 코드인지)") },
            placeholder = { Text("예: 동생") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                NumberField(
                    label = "사용 횟수",
                    value = maxUses,
                    onValueChange = { maxUses = it },
                    suffix = "회",
                    decimal = false,
                )
            }
            Box(Modifier.weight(1f)) {
                NumberField(
                    label = "유효기간",
                    value = ttlHours,
                    onValueChange = { ttlHours = it },
                    suffix = "시간",
                    decimal = false,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "1회 / 72시간을 권합니다. 유효기간을 비우면 만료되지 않습니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                vm.createInvite(
                    label = label,
                    maxUses = maxUses.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    ttlHours = ttlHours.toDoubleOrNull(),
                )
                label = ""
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("코드 발급")
        }
    }
}

/** 방금 발급한 코드. 복사와 공유가 여기서 바로 되어야 쓸모가 있다. */
@Composable
private fun NewInviteCard(invite: Invite, serverUrl: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "발급된 초대코드",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    invite.code,
                    fontSize = 28.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append("${invite.maxUses}회 사용 가능")
                    invite.expiresAt?.let { append(" · ${formatTs(it)} 만료") }
                        ?: append(" · 만료 없음")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(invite.code)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("복사")
                }
                Button(
                    onClick = {
                        val text = "elemensha copy 초대\n\n" +
                            "서버 주소: ${serverUrl.ifBlank { "리더에게 확인" }}\n" +
                            "초대코드: ${invite.code}\n\n" +
                            "앱에서 위 두 값을 입력하면 가입됩니다."
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "초대코드 보내기",
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("공유")
                }
            }
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    }
}

@Composable
private fun InviteRow(invite: Invite, onDelete: () -> Unit, busy: Boolean) {
    val used = invite.exhausted
    val expired = invite.expiresAt?.let { it * 1000 < System.currentTimeMillis() } == true

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                invite.code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = if (used || expired) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    invite.label?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                    append("${invite.uses}/${invite.maxUses}회")
                    when {
                        used -> append(" · 소진됨")
                        expired -> append(" · 만료됨")
                        invite.expiresAt != null ->
                            append(" · ${formatTs(invite.expiresAt)} 까지")
                        else -> append(" · 만료 없음")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    used || expired -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> ProfitGreen
                },
            )
        }
        TextButton(onClick = onDelete, enabled = !busy) {
            Text("폐기", color = LossRed)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ------------------------------------------------------------- 팔로워 카드

@Composable
private fun FollowerCard(
    follower: FollowerRow,
    busy: Boolean,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(
        title = follower.label.ifBlank { "팔로워 #${follower.id}" },
        subtitle = buildString {
            append(if (follower.running) "카피 중" else "정지됨")
            append(" · 기기 ${follower.devices}대")
            follower.createdAt?.let { append(" · ${formatTs(it)} 가입") }
        },
    ) {
        StatRow(
            "상태",
            if (follower.running) "실행 중" else "정지",
            if (follower.running) ProfitGreen else WarnAmber,
        )
        StatRow("주문 크기", sizingLabel(follower.sizingMode))
        StatRow(
            "API 키",
            if (follower.credentials.configured)
                follower.credentials.apiKeyMasked +
                    (if (follower.credentials.testnet) " (테스트넷)" else "")
            else "미등록",
            if (follower.credentials.configured) ProfitGreen else WarnAmber,
        )
        follower.lastError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = LossRed)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onStop,
                enabled = !busy && follower.running,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("카피 정지")
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LossRed),
                modifier = Modifier.weight(1f),
            ) { Text("계정 삭제") }
        }
    }
}

private fun sizingLabel(mode: String?): String = when (mode) {
    "equity" -> "자산 비례"
    "multiplier" -> "고정 배수"
    "fixed" -> "고정 금액"
    else -> "—"
}

private val inviteTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTs(ts: Double): String =
    inviteTimeFormat.format(Date((ts * 1000).toLong()))
