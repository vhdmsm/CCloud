package com.pira.ccloud.data.repository

import com.pira.ccloud.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Signing in to GitHub on a TV without typing a token: the TV shows a short code, and the user
 * approves it on their phone at github.com/login/device (GitHub's device flow). It uses the public
 * client id of a GitHub App that may only write to the snapshot's repository; the app carries no
 * secret, and the token it gets is kept on the device only.
 */
object GitHubDeviceLogin {
    // Public, not a secret (set by the build); empty hides the sign-in
    val clientId: String get() = BuildConfig.METADATA_SNAPSHOT_CLIENT_ID
    val isConfigured: Boolean get() = clientId.isNotBlank()

    data class Code(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSec: Long, val expiresAt: Long)

    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Asks GitHub for a code to show; the user enters it at [Code.verificationUri]. */
    suspend fun start(): Code = withContext(Dispatchers.IO) {
        val json = post("https://github.com/login/device/code", FormBody.Builder().add("client_id", clientId).build())
        json.error()?.let { throw IllegalStateException(it) }
        Code(
            deviceCode = json.string("device_code")!!,
            userCode = json.string("user_code")!!,
            verificationUri = json.string("verification_uri") ?: "https://github.com/login/device",
            intervalSec = json["interval"]?.jsonPrimitive?.longOrNull ?: 5L,
            expiresAt = System.currentTimeMillis() + (json["expires_in"]?.jsonPrimitive?.longOrNull ?: 900L) * 1000
        )
    }

    /** Waits until the user approved [code] on their phone; fails when they declined or it expired. */
    suspend fun awaitTokens(code: Code): Tokens {
        var interval = code.intervalSec
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(interval * 1000)
            val json = withContext(Dispatchers.IO) {
                post(
                    "https://github.com/login/oauth/access_token",
                    FormBody.Builder()
                        .add("client_id", clientId)
                        .add("device_code", code.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .build()
                )
            }
            when (json.string("error")) {
                null -> return json.tokens()
                "authorization_pending" -> Unit
                // GitHub asks to wait longer between checks
                "slow_down" -> interval = json["interval"]?.jsonPrimitive?.longOrNull ?: (interval + 5)
                "access_denied" -> throw IllegalStateException("Sign-in was declined")
                "expired_token" -> throw IllegalStateException("The code expired, try again")
                else -> throw IllegalStateException(json.error() ?: "Sign-in failed")
            }
        }
        throw IllegalStateException("The code expired, try again")
    }

    /** A new token for an expired one (GitHub Apps whose user tokens expire); null when it can't be renewed. */
    suspend fun refresh(refreshToken: String): Tokens? = withContext(Dispatchers.IO) {
        try {
            val json = post(
                "https://github.com/login/oauth/access_token",
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build()
            )
            if (json.string("error") != null) null else json.tokens()
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.tokens(): Tokens {
        val now = System.currentTimeMillis()
        return Tokens(
            accessToken = string("access_token") ?: throw IllegalStateException("No token in GitHub's answer"),
            refreshToken = string("refresh_token"),
            expiresAt = this["expires_in"]?.jsonPrimitive?.longOrNull?.let { now + it * 1000 }
        )
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.error(): String? = string("error_description") ?: string("error")

    private fun post(url: String, form: FormBody): JsonObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").post(form).build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw IllegalStateException("GitHub ${response.code}")
            }
        }
    }
}
