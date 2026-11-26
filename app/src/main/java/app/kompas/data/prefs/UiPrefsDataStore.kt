package app.kompas.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.kompas.domain.model.AppLanguage
import app.kompas.domain.model.ThemePref
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiPrefsDataStore by preferencesDataStore(name = "ui_prefs")

class UiPrefs(private val context: Context) {
    val language: Flow<AppLanguage> = context.uiPrefsDataStore.data.map { prefs ->
        when (prefs[LANGUAGE]) {
            "pl" -> AppLanguage.PL
            "en" -> AppLanguage.EN
            else -> AppLanguage.SYSTEM
        }
    }
    val theme: Flow<ThemePref> = context.uiPrefsDataStore.data.map { prefs ->
        when (prefs[THEME]) {
            "light" -> ThemePref.LIGHT
            "dark" -> ThemePref.DARK
            else -> ThemePref.SYSTEM
        }
    }
    val onboardingCompleted: Flow<Boolean> = context.uiPrefsDataStore.data.map { prefs ->
        prefs[ONBOARDING] == true
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.uiPrefsDataStore.edit { prefs ->
            prefs[LANGUAGE] = when (language) {
                AppLanguage.PL -> "pl"
                AppLanguage.EN -> "en"
                AppLanguage.SYSTEM -> "system"
            }
        }
    }

    suspend fun setTheme(theme: ThemePref) {
        context.uiPrefsDataStore.edit { prefs ->
            prefs[THEME] = when (theme) {
                ThemePref.LIGHT -> "light"
                ThemePref.DARK -> "dark"
                ThemePref.SYSTEM -> "system"
            }
        }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.uiPrefsDataStore.edit { it[ONBOARDING] = done }
    }

    suspend fun clear() {
        context.uiPrefsDataStore.edit { it.clear() }
    }

    companion object {
        private val LANGUAGE: Preferences.Key<String> = stringPreferencesKey("app_language")
        private val THEME: Preferences.Key<String> = stringPreferencesKey("theme")
        private val ONBOARDING: Preferences.Key<Boolean> = booleanPreferencesKey("onboarding_completed")
    }
}
