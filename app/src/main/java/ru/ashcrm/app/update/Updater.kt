package ru.ashcrm.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import okhttp3.Request
import ru.ashcrm.app.BuildConfig
import ru.ashcrm.app.data.Portal
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.net.AppInfo
import ru.ashcrm.app.net.CrmApi
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What the app does about its version (docs/ANDROID_CONTRACT.md §2.2). */
sealed interface UpdateDecision {
    /** The CRM does not know the app: a shell of the PWA, no notifications. */
    data object Unsupported : UpdateDecision
    data object UpToDate : UpdateDecision
    /** A newer version, offered once; «Позже» until the next one. */
    data class Optional(val info: AppInfo) : UpdateDecision
    /** Another major contract: no «Позже», notifications are not registered. */
    data class Mandatory(val info: AppInfo) : UpdateDecision
    /** The app is newer than the CRM: nothing to install, the administrator must update the CRM. */
    data class CrmTooOld(val info: AppInfo) : UpdateDecision
}

object VersionRules {
    fun major(contract: String) = contract.substringBefore('.').toIntOrNull() ?: -1

    fun decide(info: AppInfo?, ownCode: Int, ownContract: String, declined: Int?): UpdateDecision = when {
        info == null -> UpdateDecision.Unsupported
        major(info.contract) != major(ownContract) ->
            if (info.versionCode > ownCode) UpdateDecision.Mandatory(info) else UpdateDecision.CrmTooOld(info)
        info.versionCode > ownCode && declined != info.versionCode -> UpdateDecision.Optional(info)
        else -> UpdateDecision.UpToDate
    }
}

class Updater(private val ctx: Context, private val prefs: Prefs, private val api: CrmApi) {

    suspend fun check(portal: String): UpdateDecision {
        val d = VersionRules.decide(api.appInfo(portal), BuildConfig.VERSION_CODE, BuildConfig.CONTRACT, prefs.get(Prefs.DECLINED_VERSION))
        prefs.set(Prefs.UPDATE_CHECKED_AT, System.currentTimeMillis())
        prefs.set(Prefs.BLOCKED, d is UpdateDecision.Mandatory || d is UpdateDecision.CrmTooOld)
        return d
    }

    suspend fun decline(info: AppInfo) = prefs.set(Prefs.DECLINED_VERSION, info.versionCode)

    /** Android lets an app install others only after the person allowed it in the settings, once. */
    fun canInstall() = ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${ctx.packageName}".toUri()
    )

    /** Downloads the APK and checks its SHA-256 and that it is signed like this app; throws with a text for a person. */
    suspend fun download(portal: String, info: AppInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val path = Portal.safePath(info.url) ?: throw IOException("CRM дала неверную ссылку на файл")
        val file = File(ctx.cacheDir, "update.apk").apply { delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        api.http.newCall(Request.Builder().url(portal + path).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Не удалось скачать обновление: ответ ${res.code}")
            val total = res.body.contentLength().takeIf { it > 0 } ?: info.size
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
        if (!hex.equals(info.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("Файл обновления повреждён (не совпала контрольная сумма)")
        }
        if (!sameSigner(file)) {
            file.delete()
            throw IOException("Файл обновления подписан другим ключом — установка отменена")
        }
        file
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

    /** Hands the checked file to the system installer; it asks the person to confirm. */
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
}
