package ru.ashcrm.app.net

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.ashcrm.app.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The user agent of every request and of the WebView (docs/ANDROID_CONTRACT.md §1). */
val APP_AGENT = "AshCRM-Android/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; contract ${BuildConfig.CONTRACT})"

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** An answer of the CRM other than 2xx: [status] decides, [message] is for a person. */
class HttpError(val status: Int, val code: String?, message: String?) : IOException(message ?: "HTTP $status")

@Serializable
data class DeviceTokens(
    @SerialName("access_token") val access: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("refresh_token") val refresh: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("session_id") val sessionId: String = "",
)

@Serializable
data class Me(@SerialName("display_name") val displayName: String = "", @SerialName("tenant_name") val tenantName: String = "")

/** GET /app/android of the CRM: only the contract matters since the app updates from GitVerse (§2). */
@Serializable
data class AppInfo(val contract: String)

@Serializable
data class Subscription(val endpoint: String, val p256dh: String, val auth: String, val platform: String = "android")

@Serializable
data class WebCode(val code: String)

@Serializable
private data class TokenRequest(val code: String, val verifier: String, @SerialName("device_key") val deviceKey: String? = null)

@Serializable
private data class RefreshRequest(
    @SerialName("refresh_token") val refresh: String,
    @SerialName("device_key") val deviceKey: String? = null,
    @SerialName("device_ts") val ts: Long? = null,
    @SerialName("device_sig") val sig: String? = null,
)

@Serializable
private data class ErrorBody(val error: ErrorInfo? = null)

@Serializable
private data class ErrorInfo(val code: String? = null, val message: String? = null)

/** REST of the CRM that the app itself uses; everything else is the page in the WebView. */
class CrmApi(base: OkHttpClient = OkHttpClient()) {
    val http: OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", APP_AGENT).build()) }
        .build()

    private val jsonType = "application/json".toMediaType()

    private suspend fun call(portal: String, method: String, path: String, body: String?, access: String?): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$portal/api/v1$path")
                // OkHttp refuses a POST without a body: /push/test and /auth/logout have no data, they get an empty one.
                .method(method, body?.toRequestBody(jsonType) ?: if (method == "GET" || method == "HEAD") null else ByteArray(0).toRequestBody())
                .apply { if (access != null) header("Authorization", "Bearer $access") }
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body.string()
                if (!res.isSuccessful) {
                    val err = runCatching { json.decodeFromString<ErrorBody>(text).error }.getOrNull()
                    throw HttpError(res.code, err?.code, err?.message)
                }
                text
            }
        }

    /** Null — the CRM does not know the app (404): it works as a shell of the PWA without notifications. */
    suspend fun appInfo(portal: String): AppInfo? = try {
        json.decodeFromString<AppInfo>(call(portal, "GET", "/app/android", null, null))
    } catch (e: HttpError) {
        if (e.status == 404) null else throw e
    }

    /** §3: the code of the connect page → keys; [deviceKey] — the public key of the phone (§3.3, ignored before 1.3). */
    suspend fun token(portal: String, code: String, verifier: String, deviceKey: String? = null): DeviceTokens =
        json.decodeFromString(call(portal, "POST", "/auth/desktop/token", json.encodeToString(TokenRequest(code, verifier, deviceKey)), null))

    /** §3.3: the refresh is signed by the key of the phone; a CRM before 1.3 ignores the extra fields. */
    suspend fun refresh(portal: String, refresh: String, deviceKey: String? = null, ts: Long? = null, sig: String? = null): DeviceTokens =
        json.decodeFromString(call(portal, "POST", "/auth/desktop/refresh", json.encodeToString(RefreshRequest(refresh, deviceKey, ts, sig)), null))

    suspend fun me(portal: String, access: String): Me = json.decodeFromString(call(portal, "GET", "/me", null, access))

    suspend fun logout(portal: String, access: String) {
        call(portal, "POST", "/auth/logout", null, access)
    }

    suspend fun subscribe(portal: String, access: String, sub: Subscription) {
        call(portal, "POST", "/push/subscriptions", json.encodeToString(sub), access)
    }

    suspend fun unsubscribe(portal: String, access: String, endpoint: String) {
        call(portal, "DELETE", "/push/subscriptions", json.encodeToString(mapOf("endpoint" to endpoint)), access)
    }

    suspend fun pushTest(portal: String, access: String): JsonObject =
        json.decodeFromString(call(portal, "POST", "/push/test", null, access))

    /** §3.2: a one-time code that opens a web session of the same employee; null — the CRM does not know it yet (404). */
    suspend fun webCode(portal: String, access: String): String? = try {
        json.decodeFromString<WebCode>(call(portal, "POST", "/auth/app/web-code", null, access)).code
    } catch (e: HttpError) {
        if (e.status == 404) null else throw e
    }

    suspend fun notifyRead(portal: String, access: String, key: String) {
        call(portal, "POST", "/notify/read", json.encodeToString(mapOf("key" to key)), access)
    }
}
