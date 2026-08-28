package com.elemensha.copy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, override val message: String) : Exception(message)

/**
 * 팔로워용 서버 클라이언트.
 *
 * 여기서 부르는 경로는 전부 `/api/copy/` 아래다. 리더 전용 경로는 아예 담지
 * 않는다 — 팔로워 토큰으로는 서버가 401 로 막지만, 앱에도 그 문이 없는 편이
 * 실수할 여지가 적다.
 *
 * 서버 주소는 반드시 https 여야 한다 (API 키가 오가는 경로라 매니페스트에서
 * 평문 http 를 막아두었다).
 */
class Api(private val prefs: Prefs) {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val downloadClient: OkHttpClient = client.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String {
        val base = prefs.serverUrl.trimEnd('/')
        require(base.isNotBlank()) { "서버 주소가 설정되지 않았습니다." }
        return "$base$path"
    }

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        if (prefs.token.isNotBlank()) b.header("Authorization", "Bearer ${prefs.token}")
        return b
    }

    private suspend fun call(request: Request): String =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val c = client.newCall(request)
                cont.invokeOnCancellation { c.cancel() }
                c.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(
                            ApiException(0, "서버에 연결할 수 없습니다: ${e.message}")
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (it.isSuccessful) {
                                cont.resume(body)
                            } else {
                                val detail = runCatching {
                                    json.decodeFromString<ApiError>(body).detail
                                }.getOrElse { _ -> body.take(200).ifBlank { "HTTP ${it.code}" } }
                                cont.resumeWithException(ApiException(it.code, detail))
                            }
                        }
                    }
                })
            }
        }

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(call(builder(path).get().build()))

    private suspend inline fun <reified T> post(path: String, body: Any? = null): T {
        val payload = when (body) {
            null -> "{}"
            is String -> body
            else -> body.toString()
        }
        return json.decodeFromString(
            call(builder(path).post(payload.toRequestBody(jsonMedia)).build())
        )
    }

    // ------------------------------------------------------------------ 가입

    suspend fun health(): Health = get("/api/health")

    /** 리더에게 받은 초대코드로 내 카피 계정을 만든다. */
    suspend fun join(serverUrl: String, code: String, label: String): JoinResponse {
        prefs.serverUrl = serverUrl
        prefs.token = ""
        val payload = buildJsonObject {
            put("code", JsonPrimitive(code))
            put("label", JsonPrimitive(label))
        }
        val result: JoinResponse = post("/api/copy/join", payload)
        prefs.token = result.token
        prefs.followerId = result.followerId
        prefs.label = result.label
        return result
    }

    suspend fun meta(): CopyMeta = get("/api/copy/meta")

    suspend fun me(): MeResponse = get("/api/copy/me")

    // ----------------------------------------------------------- 내 API 키

    suspend fun credentials(): CredentialInfo = get("/api/copy/credentials")

    suspend fun setCredentials(
        apiKey: String,
        apiSecret: String,
        testnet: Boolean,
    ): CredentialInfo = post(
        "/api/copy/credentials",
        buildJsonObject {
            put("apiKey", JsonPrimitive(apiKey))
            put("apiSecret", JsonPrimitive(apiSecret))
            put("testnet", JsonPrimitive(testnet))
        },
    )

    suspend fun deleteCredentials(): String =
        call(builder("/api/copy/credentials").delete().build())

    // ------------------------------------------------------------------ 설정

    suspend fun config(): CopyConfig = get("/api/copy/config")

    suspend fun saveConfig(config: CopyConfig): JsonObject =
        post("/api/copy/config", json.encodeToString(CopyConfig.serializer(), config))

    // ------------------------------------------------------------- 시작/정지

    suspend fun start(): JsonObject = post("/api/copy/start")

    suspend fun stop(): JsonObject = post("/api/copy/stop")

    /** 내 포지션만 전량 시장가 청산. 리더와 다른 팔로워는 영향받지 않는다. */
    suspend fun panic(): JsonObject = post("/api/copy/panic")

    // ------------------------------------------------------------------ 조회

    suspend fun status(): CopyStatus = get("/api/copy/status")

    /** 지금 이 순간의 내 계좌. 저장된 스냅샷이 아니다. */
    suspend fun account(): AccountSummary = get("/api/copy/account")

    /** 거래소에 실제로 살아있는 내 미체결 지정가 주문. */
    suspend fun orders(): OrdersResponse = get("/api/copy/orders")

    suspend fun positions(): PositionsResponse = get("/api/copy/positions")

    suspend fun balanceHistory(period: String): BalanceHistory =
        get("/api/copy/balance/history?period=$period")

    suspend fun snapshotNow(): JsonObject = post("/api/copy/balance/snapshot")

    suspend fun events(limit: Int = 200): EventsResponse =
        get("/api/copy/events?limit=$limit")

    /** 내 실시간 이벤트/상태 스트림. 끊기면 호출측에서 재구독한다. */
    fun stream(): Flow<WsFrame> = callbackFlow {
        val wsUrl = prefs.serverUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/copy?token=${prefs.token}"

        val socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { json.decodeFromString<WsFrame>(text) }
                        .onSuccess { trySend(it) }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    close(t)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    channel.close()
                }
            },
        )
        awaitClose { socket.cancel() }
    }

    // ---------------------------------------------------------- 인앱 업데이트

    suspend fun latestVersion(): AppVersionInfo = get("/api/copy/app/version")
}
