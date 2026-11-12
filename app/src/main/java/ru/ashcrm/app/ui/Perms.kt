package ru.ashcrm.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** The tabs of the settings of the app. */
enum class SettingsTab(val title: String) { Notifications("Уведомления"), Permissions("Разрешения"), About("О приложении") }

/** What Android gave the app: the tab «Разрешения» shows it, the page of the CRM sends there when it lacks one. */
object Perms {
    enum class Location { None, Coarse, Fine }

    fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun location(ctx: Context): Location = when {
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) -> Location.Fine
        granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) -> Location.Coarse
        else -> Location.None
    }

    fun batteryFree(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Without these the app does not do its job for anyone: the tab gets a dot. */
    fun requiredMissing(ctx: Context) = !notificationsAllowed(ctx) || !batteryFree(ctx)

    fun anyMissing(ctx: Context) = requiredMissing(ctx) || location(ctx) != Location.Fine ||
        !granted(ctx, Manifest.permission.CAMERA) || !granted(ctx, Manifest.permission.RECORD_AUDIO)

    /** The page of the app in the settings of Android: where a refused permission («Больше не спрашивать») is turned on. */
    fun openAppDetails(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))
    }
}
