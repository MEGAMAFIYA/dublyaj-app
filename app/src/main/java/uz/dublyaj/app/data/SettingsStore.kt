package uz.dublyaj.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sozlamalar")

object SettingsKeys {
    val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
    val AZURE_API_KEY = stringPreferencesKey("azure_api_key")
    val AZURE_REGION = stringPreferencesKey("azure_region")
    val HUGGINGFACE_TOKEN = stringPreferencesKey("huggingface_token")
    val GITHUB_REPO = stringPreferencesKey("github_repo")

    // "auto" | "online" | "offline"
    val PREFERRED_MODE = stringPreferencesKey("preferred_mode")
}

class SettingsStore(private val context: Context) {

    fun stringFlow(key: Preferences.Key<String>): Flow<String> =
        context.dataStore.data.map { it[key] ?: "" }

    suspend fun save(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }
}
