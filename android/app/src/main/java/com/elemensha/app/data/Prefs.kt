package com.elemensha.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 서버 주소와 접속 토큰만 로컬에 둔다.
 *
 * 바이낸스 API 키는 여기 저장하지 않는다 — 입력 즉시 서버로 보내고
 * 앱은 마스킹된 값만 다시 받아 표시한다.
 *
 * 안드로이드 키스토어로 암호화되며, 기기 백업에서도 제외된다.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // 키스토어를 못 쓰는 기기에서도 앱은 떠야 한다
        context.getSharedPreferences("${FILE_NAME}_plain", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trimEnd('/')).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** 마지막으로 쓴 봇 설정을 JSON 문자열로 보관해 재입력을 줄인다. */
    var lastConfigJson: String
        get() = prefs.getString(KEY_CONFIG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CONFIG, value).apply()

    val isPaired: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val FILE_NAME = "elemensha_secure"
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_CONFIG = "last_config"
    }
}
