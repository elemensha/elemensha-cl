package com.elemensha.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 *
 * 이 화면만 시스템 테마를 따르지 않고 항상 흰 배경으로 그린다.
 * 배경을 직접 칠하는 것도 중요하다 — Surface 없이 두면 뒤의
 * windowBackground(검정)가 비쳐 라이트 모드에서 글씨가 묻힌다.
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

    ElemenshaLightTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(48.dp))

                // 로고는 검은 배경을 전제로 만들어졌다. 흰 화면에서는
                // 어두운 배지 위에 올려야 원래 색이 산다.
                Box(
                    Modifier
                        .size(148.dp)
                        .clip(RoundedCornerShape(34.dp))
                        .background(Color(0xFF0E0E11)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = "elemensha",
                        modifier = Modifier.size(140.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
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
    }
}
