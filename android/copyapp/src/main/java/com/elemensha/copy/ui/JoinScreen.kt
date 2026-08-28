package com.elemensha.copy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elemensha.copy.R

/**
 * 최초 1회 가입 화면.
 *
 * 리더에게 받은 초대코드를 넣으면 내 카피 계정이 서버에 만들어지고
 * 이 기기의 토큰이 발급된다. 이후에는 이 화면이 다시 뜨지 않는다.
 */
@Composable
fun JoinScreen(
    connecting: Boolean,
    error: String?,
    savedUrl: String,
    onJoin: (String, String) -> Unit,
    onDismissError: () -> Unit,
) {
    var url by remember { mutableStateOf(savedUrl.ifBlank { "https://" }) }
    var code by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(40.dp))
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = "elemensha copy",
            modifier = Modifier.size(128.dp),
        )
        Text(
            "elemensha copy",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "리더의 매매를 내 계정으로 따라 합니다",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("서버 주소") },
            placeholder = { Text("https://elemensha-claude.duckdns.org") },
            supportingText = { Text("리더가 알려준 https 주소입니다.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text("초대코드") },
            placeholder = { Text("A1B2-C3D4") },
            supportingText = { Text("리더에게 받은 1회용 코드입니다.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onJoin(url, code) },
            enabled = !connecting && url.length > 10 && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text("가입하고 연결", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("가입 후 할 일", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. [더보기 > API 키]에서 내 바이낸스 키를 등록합니다.\n" +
                    "2. [설정]에서 주문 크기 방식을 고릅니다.\n" +
                    "3. [설정]에서 카피를 시작합니다.\n\n" +
                    "내 API 키는 서버에 암호화되어 저장되며, 리더는 마스킹된 " +
                    "값만 볼 수 있습니다. 키 발급 시 출금 권한은 절대 켜지 마세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                    )
                    TextButton(onClick = onDismissError) { Text("닫기") }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
