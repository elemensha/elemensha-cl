package com.elemensha.app.data

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
 * 서버 REST + WebSocket 클라이언트.
 *
 * 모든 호출은 Bearer 토큰을 붙인다. 서버 주소는 반드시 https 여야 한다
 * (API 키가 오가는 경로이므로 매니페스트에서 평문 http 를 막아두었다).
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
        .pingInterval(20, TimeUnit.SECONDS)   // WebSocket 유지
        .retryOnConnectionFailure(true)
        .build()

    /** APK 다운로드는 오래 걸릴 수 있어 타임아웃을 따로 둔다. */
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

    // ------------------------------------------------------------- 연결/페어링

    suspend fun health(): Health = get("/api/health")

    suspend fun pair(serverUrl: String, code: String, label: String): PairResponse {
        prefs.serverUrl = serverUrl
        prefs.token = ""
        val payload = buildJsonObject {
            put("code", JsonPrimitive(code))
            put("label", JsonPrimitive(label))
        }
        val result: PairResponse = post("/api/pair", payload)
        prefs.token = result.token
        return result
    }

    suspend fun meta(): Meta = get("/api/meta")

    // --------------------------------------------------------- API 키 [요구사항 7]

    suspend fun credentials(): CredentialInfo = get("/api/credentials")

    suspend fun setCredentials(
        apiKey: String,
        apiSecret: String,
        testnet: Boolean,
    ): CredentialInfo = post(
        "/api/credentials",
        buildJsonObject {
            put("apiKey", JsonPrimitive(apiKey))
            put("apiSecret", JsonPrimitive(apiSecret))
            put("testnet", JsonPrimitive(testnet))
        },
    )

    suspend fun deleteCredentials(): String =
        call(builder("/api/credentials").delete().build())

    // ----------------------------------------------------------------- 심볼

    suspend fun symbols(refresh: Boolean = false): SymbolsResponse =
        get("/api/symbols?refresh=$refresh")

    // ------------------------------------------------- 레버리지/마진 [요구사항 1]

    suspend fun exchangeSettings(symbol: String): ExchangeSettings =
        get("/api/exchange-settings?symbol=${encode(symbol)}")

    /** 바이낸스에 적용하고, 되읽어 확인한 결과를 돌려준다. */
    suspend fun applyExchangeSettings(
        symbol: String,
        leverage: Int,
        marginMode: String,
    ): ExchangeSettingsResult = post(
        "/api/exchange-settings",
        buildJsonObject {
            put("symbol", JsonPrimitive(symbol))
            put("leverage", JsonPrimitive(leverage))
            put("marginMode", JsonPrimitive(marginMode))
        },
    )

    // ------------------------------------------------------------------- 봇

    suspend fun bots(): BotsResponse = get("/api/bots")

    suspend fun startBot(config: BotConfig): JsonObject =
        post("/api/bots/start", json.encodeToString(BotConfig.serializer(), config))

    suspend fun stopBot(symbol: String): JsonObject =
        post("/api/bots/${encode(symbol)}/stop")

    suspend fun panicBot(symbol: String): JsonObject =
        post("/api/bots/${encode(symbol)}/panic")

    suspend fun panicAll(): JsonObject = post("/api/panic-all")

    suspend fun deleteBot(symbol: String): String =
        call(builder("/api/bots/${encode(symbol)}").delete().build())

    // ------------------------------------------------------------ 잔고 그래프

    suspend fun balanceHistory(period: String): BalanceHistory =
        get("/api/balance/history?period=$period")

    suspend fun snapshotNow(): JsonObject = post("/api/balance/snapshot")

    // ---------------------------------------------------------------- 이벤트

    suspend fun events(limit: Int = 200): EventsResponse = get("/api/events?limit=$limit")

    /** 실시간 이벤트/상태 스트림. 끊기면 호출측에서 재구독한다. */
    fun stream(): Flow<WsFrame> = callbackFlow {
        val wsUrl = prefs.serverUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws?token=${prefs.token}"

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

    suspend fun latestVersion(): AppVersionInfo = get("/api/app/version")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
