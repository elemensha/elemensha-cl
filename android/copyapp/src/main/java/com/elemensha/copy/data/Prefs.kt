package com.elemensha.copy.data

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
 * 파일 이름을 리더 앱과 다르게 둔다. 한 기기에 두 앱을 함께 깔았을 때
 * 팔로워 토큰과 리더 토큰이 섞이면 서로의 화면이 열릴 수 있다.
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

    var followerId: Int
        get() = prefs.getInt(KEY_FOLLOWER_ID, 0)
        set(value) = prefs.edit().putInt(KEY_FOLLOWER_ID, value).apply()

    var label: String
        get() = prefs.getString(KEY_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LABEL, value).apply()

    val isJoined: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val FILE_NAME = "elemensha_copy_secure"
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_FOLLOWER_ID = "follower_id"
        const val KEY_LABEL = "label"
    }
}
