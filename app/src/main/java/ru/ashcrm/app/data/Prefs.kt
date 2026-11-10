package ru.ashcrm.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private val Context.store by preferencesDataStore("ashcrm")

/** Plain settings of the app; secrets go through [SecureBox] first. */
class Prefs(context: Context) {
    private val store = context.store
    val data: Flow<Preferences> = store.data

    suspend fun <T> get(key: Preferences.Key<T>): T? = store.data.first()[key]
    suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        store.edit { if (value == null) it.remove(key) else it[key] = value }
    }

    companion object {
        val PORTAL = stringPreferencesKey("portal")
        /** The sealed refresh key of the device session and who it belongs to. */
        val REFRESH = stringPreferencesKey("refresh")
        val USER_NAME = stringPreferencesKey("user_name")
        val TENANT_NAME = stringPreferencesKey("tenant_name")
        /** The sealed keys of push encryption (PKCS#8 private, public point, auth secret). */
        val PUSH_PRIVATE = stringPreferencesKey("push_private")
        val PUSH_PUBLIC = stringPreferencesKey("push_public")
        val PUSH_AUTH = stringPreferencesKey("push_auth")
        /** The endpoint registered at the CRM (`fcm:<token>`) and when. */
        /** The FCM token of this install, as FCM last reported it in onRegistered. */
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val REGISTERED_AT = longPreferencesKey("registered_at")
        val REGISTER_ERROR = stringPreferencesKey("register_error")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val AUTOSTART_DONE = booleanPreferencesKey("autostart_done")
        /** «Не сейчас» on the connect page: the app does not open it by itself until the next login. */
        val CONNECT_SKIPPED = booleanPreferencesKey("connect_skipped")
        /** «Позже» on an update: the version declined until a newer one appears. */
        val DECLINED_VERSION = intPreferencesKey("declined_version")
        val UPDATE_CHECKED_AT = longPreferencesKey("update_checked_at")
        /** The contract of the CRM is another major version: notifications are not registered until the update. */
        val BLOCKED = booleanPreferencesKey("blocked")
    }
}
