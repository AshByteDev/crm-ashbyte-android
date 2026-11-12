package ru.ashcrm.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import ru.ashcrm.app.BuildConfig
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.net.CrmApi
import ru.ashcrm.app.net.HttpError
import ru.ashcrm.app.net.json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A release of the app: `release/android.json` of the repository on GitVerse, it points at `ashcrm.apk` (README → «Выпуск версии»). */
@Serializable
data class Release(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    /** Below it the update is a must (no «Позже»). */
    @SerialName("min_version_code") val minVersionCode: Int = 0,
    val sha256: String,
    val size: Long = 0,
    val url: String,
    /** «Что нового»: a few short lines. */
    val notes: List<String> = emptyList(),
)

/** What the CRM says about the app (docs/ANDROID_CONTRACT.md §2): only whether it knows it and the contract. */
enum class CrmState {
    /** No answer (no network): decided later. */
    Unknown,
    /** The CRM has no API of the app: a shell of the PWA without notifications. */
    Unsupported,
    Supported,
    /** Another major version of the contract: notifications are not registered until the versions meet. */
    Incompatible,
}

/** What the app does about its version (owner 12.11: versions and the APK come from GitVerse). */
sealed interface UpdateDecision {
    data object None : UpdateDecision
    /** A newer version, offered once; «Позже» until the next one. */
    data class Optional(val release: Release) : UpdateDecision
    /** Below `min_version_code`, or the CRM speaks another contract and GitVerse has a newer app: no «Позже». */
    data class Mandatory(val release: Release) : UpdateDecision
}

object VersionRules {
    fun major(contract: String) = contract.substringBefore('.').toIntOrNull() ?: -1

    fun crm(contract: String, own: String = BuildConfig.CONTRACT): CrmState = when {
        major(contract) == major(own) -> CrmState.Supported
        else -> CrmState.Incompatible
    }

    fun decide(release: Release?, ownCode: Int, declined: Int?, crm: CrmState): UpdateDecision = when {
        release == null || release.versionCode <= ownCode -> UpdateDecision.None
        ownCode < release.minVersionCode || crm == CrmState.Incompatible -> UpdateDecision.Mandatory(release)
        declined != release.versionCode -> UpdateDecision.Optional(release)
        else -> UpdateDecision.None
    }
}

class Updater(private val ctx: Context, private val prefs: Prefs, private val api: CrmApi) {

    /** Does the CRM know the app (GET /app/android)? Throws on no network — the caller keeps [CrmState.Unknown]. */
    suspend fun crm(portal: String): CrmState {
        val info = api.appInfo(portal) ?: return CrmState.Unsupported.also { prefs.set(Prefs.BLOCKED, null) }
        return VersionRules.crm(info.contract).also { prefs.set(Prefs.BLOCKED, it == CrmState.Incompatible) }
    }

    /** The latest release; null — GitVerse did not answer (blocked, no network, too many requests): silently, next time. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            api.http.newCall(Request.Builder().url(BuildConfig.UPDATE_MANIFEST).build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                json.decodeFromString<Release>(res.body.string()).takeIf { releaseUrlOk(it.url) }
            }
        }.getOrNull()
    }

    suspend fun decide(crm: CrmState): UpdateDecision {
        val release = latest()
        if (release != null) prefs.set(Prefs.UPDATE_CHECKED_AT, System.currentTimeMillis())
        return VersionRules.decide(release, BuildConfig.VERSION_CODE, prefs.get(Prefs.DECLINED_VERSION), crm)
    }

    suspend fun decline(release: Release) = prefs.set(Prefs.DECLINED_VERSION, release.versionCode)

    /** Android lets an app install others only after the person allowed it in the settings, once. */
    fun canInstall() = ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${ctx.packageName}".toUri()
    )

    /**
     * Downloads the APK of [release] from GitVerse and checks its SHA-256 and that it is signed like this app; throws
     * with a text for a person. Only a file of a release, only over https, from gitverse.ru. GitVerse answers bursts of
     * requests (phones of one office behind one address) with 429: wait and try again.
     */
    suspend fun download(release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (!releaseUrlOk(release.url)) throw IOException("Неверная ссылка на обновление")
        val file = File(ctx.cacheDir, "update.apk").apply { delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        fetchWaitingWhenBusy(release.url).use { res ->
            if (res.code == 429) throw HttpError(res.code, null, "Сервер обновлений занят — попробуйте через минуту")
            if (!res.isSuccessful) throw HttpError(res.code, null, "Не удалось скачать обновление: ответ ${res.code}")
            if (res.request.url.host !in DOWNLOAD_HOSTS) throw IOException("Обновление пришло не с GitVerse — отменено")
            val total = res.body.contentLength().takeIf { it > 0 } ?: release.size
            res.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("Файл обновления повреждён (не совпала контрольная сумма)")
        }
        if (!sameSigner(file)) {
            file.delete()
            throw IOException("Файл обновления подписан другим ключом — установка отменена")
        }
        file
    }

    /** GET [url]; on 429 waits (Retry-After or [BUSY_WAITS]) and tries again; the last answer is returned as it is. */
    private suspend fun fetchWaitingWhenBusy(url: String): okhttp3.Response {
        for (wait in BUSY_WAITS) {
            val res = api.http.newCall(Request.Builder().url(url).build()).execute()
            if (res.code != 429) return res
            val pause = res.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60)?.times(1000) ?: wait
            res.close()
            kotlinx.coroutines.delay(pause)
        }
        return api.http.newCall(Request.Builder().url(url).build()).execute()
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(apk: File): Boolean {
        val pm = ctx.packageManager
        fun certs(info: PackageInfo?): Set<String> {
            info ?: return emptySet()
            val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return sigs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != ctx.packageName) return false
        val own = certs(pm.getPackageInfo(ctx.packageName, flags))
        return own.isNotEmpty() && certs(archive) == own
    }

    /** Hands the checked file to the system installer; it asks the person to confirm (not on later self-updates). */
    fun install(apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val status = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            s.commit(status.intentSender)
        }
    }

    companion object {
        /** GitVerse gives the file of a release itself, without a redirect (checked 12.11.2026). */
        val DOWNLOAD_HOSTS = setOf("gitverse.ru")

        /** Pauses after a 429 before the next try. */
        private val BUSY_WAITS = longArrayOf(5_000, 15_000, 30_000)

        private val ATTACHMENT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** Only a file of a release (`<prefix><uuid>`), over https. */
        fun releaseUrlOk(url: String, prefix: String = BuildConfig.RELEASES_PREFIX) =
            url.startsWith(prefix) && ATTACHMENT.matches(url.removePrefix(prefix))
    }
}
