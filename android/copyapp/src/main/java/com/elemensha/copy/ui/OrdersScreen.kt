package com.elemensha.copy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elemensha.copy.CopyUiState
import com.elemensha.copy.CopyViewModel
import com.elemensha.copy.data.OpenOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 내 미체결 지정가 주문 화면.
 *
 * [사용자 요청] 지정가 주문 상태를 알아야 한다.
 * 서버가 기억하는 값이 아니라 거래소에 직접 물어본 결과를 보여준다 —
 * 둘이 어긋나는 순간이 바로 확인이 필요한 순간이기 때문이다.
 */
@Composable
fun OrdersScreen(vm: CopyViewModel, state: CopyUiState) {
    LaunchedEffect(state.credentialsConfigured) {
        if (state.credentialsConfigured) vm.loadOrders()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (!state.credentialsConfigured) {
            SectionCard(
                title = "API 키가 없습니다",
                subtitle = "[더보기 > API 키]에서 등록하면 주문 상태를 볼 수 있습니다.",
            ) {}
            return@Column
        }

        SectionCard(
            title = "지정가 주문",
            subtitle = "거래소에서 방금 읽어온 실제 상태입니다.",
        ) {
            val live = state.orders.filter { it.error == null }
            StatRow("미체결 주문", "${live.size}건")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = vm::loadOrders,
                enabled = !state.ordersLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.ordersLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("새로고침")
            }
        }

        if (state.orders.isEmpty() && !state.ordersLoading) {
            SectionCard(
                title = "걸려 있는 주문이 없습니다",
                subtitle = "포지션이 생기면 평단 기준 익절 지정가가 자동으로 등록됩니다.",
            ) {}
        }

        state.orders.forEach { OrderCard(it) }

        SectionCard(
            title = "주문이 어떻게 걸리나",
            subtitle = "이 앱이 대신 하는 일",
        ) {
            Text(
                "· 리더가 매수하면 내 배율만큼 시장가로 따라 삽니다.\n" +
                "· 포지션이 생기면 곧바로 '내 평단 × (1 + 익절률)'에 " +
                "전량 매도 지정가를 겁니다.\n" +
                "· 추가 매수로 평단이 바뀌면 익절 주문도 따라 옮깁니다. " +
                "값이 그대로면 건드리지 않습니다.\n" +
                "· reduceOnly 가 붙어 있어 포지션을 넘겨 파는 일은 없습니다.\n" +
                "· 손절 주문은 걸지 않습니다. 전략상 청산은 익절 하나뿐입니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OrderCard(order: OpenOrder) {
    order.error?.let {
        SectionCard(title = order.symbol, subtitle = "조회 실패") {
            Text(it, style = MaterialTheme.typography.labelSmall, color = LossRed)
        }
        return
    }

    val isTakeProfit = order.reduceOnly && order.side == "sell"
    val distance = order.distancePercent

    SectionCard(
        title = order.symbol,
        subtitle = buildString {
            append(if (isTakeProfit) "익절 지정가" else "${order.side ?: ""} ${order.type ?: ""}")
            order.status?.let { append(" · $it") }
        },
    ) {
        StatRow("지정가", "$" + order.price.asPrice(),
                if (isTakeProfit) ProfitGreen else null)
        order.markPrice?.let { StatRow("현재가", "$" + it.asPrice()) }

        distance?.let {
            // 양수 = 현재가가 익절가보다 아래. 아직 더 올라야 팔린다.
            StatRow(
                "익절까지",
                if (it > 0) "%.2f%% 남음".format(it) else "%.2f%% 초과".format(-it),
                if (it > 0) WarnAmber else ProfitGreen,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        StatRow("주문 수량", order.amount.asPrice())
        if (order.filled > 0) {
            StatRow("체결", order.filled.asPrice(), ProfitGreen)
            StatRow("남은 수량", order.remaining.asPrice())
            val percent = if (order.amount > 0) order.filled / order.amount else 0.0
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { percent.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "부분 체결 ${"%.1f".format(percent * 100)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatRow("주문 금액", order.notional.asUsd())

        if (order.reduceOnly) {
            StatRow("reduceOnly", "적용됨", ProfitGreen)
        }
        order.createdAt?.let {
            StatRow("등록 시각", formatMillis(it))
        }
        if (order.orderId.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "주문번호 ${order.orderId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

private val orderTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun formatMillis(ms: Long): String = orderTimeFormat.format(Date(ms))
