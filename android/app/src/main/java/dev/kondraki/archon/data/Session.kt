package dev.kondraki.archon.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The deck session (tasks #117 decision 6): PIN once per phone, then the JWT lives here,
 * encrypted with an Android Keystore AES-GCM key.
 *
 * The key deliberately does NOT require user authentication: Quick Settings tiles and
 * shortcuts act from the background and must be able to read the token. The fingerprint
 * gates the app's UI (see ui/Unlock.kt), not the key.
 */
class Session(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _token = MutableStateFlow(load())
    val token: StateFlow<String?> = _token

    suspend fun login(pin: String): LoginResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${Deck.url}/login")
            .post(FormBody.Builder().add("pin", pin).build()).build()
        runCatching {
            Deck.noRedirects.newCall(req).execute().use { r ->
                loginOutcome(r.code, r.header("Location"), r.headers("Set-Cookie"))
            }
        }.getOrElse { LoginResult.Failed(it.message ?: it.javaClass.simpleName) }
            .also { if (it is LoginResult.Ok) save(it.token) }
    }

    /** Swap the token for a fresh one when under 5 days remain (POST /auth/refresh). A 401
     *  means the server no longer accepts it: forget it, so the PIN screen comes back. */
    suspend fun refreshIfDue() = withContext(Dispatchers.IO) {
        val t = _token.value ?: return@withContext
        if (!refreshDue(jwtExp(t), System.currentTimeMillis() / 1000)) return@withContext
        runCatching {
            Deck.http.newCall(Request.Builder().url("${Deck.url}/auth/refresh")
                .post(ByteArray(0).toRequestBody()).build()).execute().use { r ->
                when {
                    r.code == 401 -> clear()
                    r.isSuccessful -> (Deck.json.parseToJsonElement(r.body.string()).jsonObject["token"] as? JsonPrimitive)
                        ?.content?.let(::save)
                }
            }
        }                                                   // offline: keep the old one, try next time
    }

    fun clear() { prefs.edit().remove(KEY).apply(); _token.value = null }

    /** Debug builds only (MainActivity checks BuildConfig.DEBUG): take a token handed over by
     *  `adb shell am start --es token …`, so the app can be tested without typing the PIN. */
    fun adopt(t: String) = save(t)

    private fun save(t: String) {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(t.toByteArray())
        prefs.edit().putString(KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        _token.value = t
    }

    private fun load(): String? = runCatching {
        val blob = Base64.decode(prefs.getString(KEY, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV))
        String(c.doFinal(blob, IV, blob.size - IV))
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY = "token"
        const val ALIAS = "archon-session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
