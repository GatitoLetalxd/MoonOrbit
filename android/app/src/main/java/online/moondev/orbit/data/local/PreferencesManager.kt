package online.moondev.orbit.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "moonorbit_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_SESSION_TOKEN = stringPreferencesKey("session_token")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        val KEY_PHONE = stringPreferencesKey("phone")
        val KEY_AVATAR_VERSION = longPreferencesKey("avatar_version")
        val KEY_GROUP_ID = stringPreferencesKey("group_id")
        val KEY_GROUP_NAME = stringPreferencesKey("group_name")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_STATUS = stringPreferencesKey("membership_status")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode") // "system", "dark", "light"
        val KEY_CLOCK_OFFSET = longPreferencesKey("clock_offset_ms")
        val KEY_PERMISSIONS_COMPLETED = booleanPreferencesKey("permissions_completed")
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:3000"

        fun httpToWsUrl(httpUrl: String): String {
            val clean = httpUrl.trimEnd('/')
            val wsScheme = if (clean.startsWith("https://")) {
                clean.replaceFirst("https://", "wss://")
            } else {
                clean.replaceFirst("http://", "ws://")
            }
            return "$wsScheme/ws"
        }
    }

    val sessionToken: Flow<String?> = context.dataStore.data.map { it[KEY_SESSION_TOKEN] }
    val userId: Flow<String?> = context.dataStore.data.map { it[KEY_USER_ID] }
    val username: Flow<String?> = context.dataStore.data.map { it[KEY_USERNAME] }
    val displayName: Flow<String?> = context.dataStore.data.map { it[KEY_DISPLAY_NAME] }
    val phone: Flow<String?> = context.dataStore.data.map { it[KEY_PHONE] }
    val avatarVersion: Flow<Long> = context.dataStore.data.map { it[KEY_AVATAR_VERSION] ?: 0L }
    val groupId: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP_ID] }
    val groupName: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP_NAME] }
    val role: Flow<String?> = context.dataStore.data.map { it[KEY_ROLE] }
    val status: Flow<String?> = context.dataStore.data.map { it[KEY_STATUS] }
    val themeMode: Flow<String> = context.dataStore.data.map { it[KEY_THEME_MODE] ?: "system" }
    val permissionsCompleted: Flow<Boolean> = context.dataStore.data.map { it[KEY_PERMISSIONS_COMPLETED] ?: false }
    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL }

    suspend fun saveSession(
        token: String,
        userId: String,
        username: String,
        displayName: String,
        phone: String? = null,
        avatarVersion: Long = 0,
        groupId: String? = null,
        groupName: String? = null,
        role: String? = null,
        status: String? = null
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SESSION_TOKEN] = token
            prefs[KEY_USER_ID] = userId
            prefs[KEY_USERNAME] = username
            prefs[KEY_DISPLAY_NAME] = displayName
            if (phone != null) prefs[KEY_PHONE] = phone else prefs.remove(KEY_PHONE)
            prefs[KEY_AVATAR_VERSION] = avatarVersion
            if (groupId != null) prefs[KEY_GROUP_ID] = groupId else prefs.remove(KEY_GROUP_ID)
            if (groupName != null) prefs[KEY_GROUP_NAME] = groupName else prefs.remove(KEY_GROUP_NAME)
            if (role != null) prefs[KEY_ROLE] = role else prefs.remove(KEY_ROLE)
            if (status != null) prefs[KEY_STATUS] = status else prefs.remove(KEY_STATUS)
        }
    }

    suspend fun updateGroup(groupId: String?, groupName: String?, role: String?, status: String?) {
        context.dataStore.edit { prefs ->
            if (groupId != null) prefs[KEY_GROUP_ID] = groupId else prefs.remove(KEY_GROUP_ID)
            if (groupName != null) prefs[KEY_GROUP_NAME] = groupName else prefs.remove(KEY_GROUP_NAME)
            if (role != null) prefs[KEY_ROLE] = role else prefs.remove(KEY_ROLE)
            if (status != null) prefs[KEY_STATUS] = status else prefs.remove(KEY_STATUS)
        }
    }

    suspend fun setPermissionsCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_PERMISSIONS_COMPLETED] = completed }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url.trimEnd('/') }
    }

    suspend fun updateAvatarVersion(version: Long) {
        context.dataStore.edit { it[KEY_AVATAR_VERSION] = version }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SESSION_TOKEN)
            prefs.remove(KEY_USER_ID)
            prefs.remove(KEY_USERNAME)
            prefs.remove(KEY_DISPLAY_NAME)
            prefs.remove(KEY_PHONE)
            prefs.remove(KEY_AVATAR_VERSION)
            prefs.remove(KEY_GROUP_ID)
            prefs.remove(KEY_GROUP_NAME)
            prefs.remove(KEY_ROLE)
            prefs.remove(KEY_STATUS)
            prefs.remove(KEY_PERMISSIONS_COMPLETED)
        }
    }
}
