package ru.ashcrm.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings

/**
 * Makers whose firmware stops apps in the background beyond stock Android (dontkillmyapp.com). There is no API to
 * read or change these switches: the app opens the right screen and the person turns it on.
 */
object Oem {
    class Step(val title: String, val hint: String, val screens: List<ComponentName>)

    private fun c(pkg: String, cls: String) = ComponentName(pkg, cls)

    fun step(): Step? {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m in setOf("xiaomi", "redmi", "poco") -> Step(
                "Автозапуск (MIUI / HyperOS)",
                "Включите «Автозапуск» для AshCRM. В «Контроль активности» выберите «Нет ограничений».",
                listOf(c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            )
            m == "huawei" -> Step(
                "Запуск приложений (EMUI)",
                "Найдите AshCRM, выключите «Управлять автоматически» и оставьте включёнными все три переключателя.",
                listOf(
                    c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ),
            )
            m == "honor" -> Step(
                "Запуск приложений (MagicOS)",
                "Найдите AshCRM, выключите «Управлять автоматически» и оставьте включёнными все три переключателя.",
                listOf(c("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            )
            m in setOf("oppo", "realme", "oneplus") -> Step(
                "Автозапуск (ColorOS)",
                "Разрешите AshCRM автозапуск и работу в фоне.",
                listOf(
                    c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                ),
            )
            m in setOf("vivo", "iqoo") -> Step(
                "Работа в фоне (Funtouch / OriginOS)",
                "Разрешите AshCRM автозапуск и высокое энергопотребление в фоне.",
                listOf(
                    c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ),
            )
            m == "samsung" -> Step(
                "Спящие приложения (One UI)",
                "Батарея → Ограничения фонового использования: AshCRM не должен быть в «Спящих» и «Глубоко спящих». Лучше добавить его в «Никогда не переводить в спящий режим».",
                listOf(c("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            )
            m == "asus" -> Step(
                "Автозапуск (ASUS)",
                "Разрешите AshCRM автозапуск в «Мобильный диспетчер».",
                listOf(c("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")),
            )
            else -> null
        }
    }

    /** Opens the first screen the firmware has; the app details as the last resort. */
    fun open(ctx: Context, step: Step) {
        for (cn in step.screens) {
            try {
                ctx.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        appDetails(ctx)
    }

    fun appDetails(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
