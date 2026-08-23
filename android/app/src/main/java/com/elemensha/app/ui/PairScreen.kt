package com.elemensha.app.ui

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
import com.elemensha.app.R

/**
 * 최초 1회 서버 연결 화면.
 *
 * 서버 부팅 로그에 찍힌 페어링 코드를 입력하면 기기 토큰을 발급받는다.
 * 이후에는 이 화면이 다시 뜨지 않는다.
 */
@Composable
fun PairScreen(
    connecting: Boolean,
    error: String?,
    savedUrl: String,
    onPair: (String, String) -> Unit,
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
        Spacer(Modifier.height(48.dp))
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = "elemensha",
            modifier = Modifier.size(140.dp),
        )
        Text(
            "elemensha",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "서버에 연결합니다",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(36.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("서버 주소") },
            placeholder = { Text("https://elemensha-claude.duckdns.org") },
            supportingText = { Text("반드시 https 주소여야 합니다.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text("페어링 코드") },
            placeholder = { Text("A1B2C3D4") },
            supportingText = {
                Text("서버에서 확인:  sudo grep PAIRING /opt/elemensha-claude-bot/.env")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onPair(url, code) },
            enabled = !connecting && url.length > 10 && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text("연결", fontWeight = FontWeight.Bold)
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
        Spacer(Modifier.height(48.dp))
    }
}
