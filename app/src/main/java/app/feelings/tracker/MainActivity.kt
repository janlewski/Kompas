package app.feelings.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.feelings.tracker.domain.model.AppLanguage
import app.feelings.tracker.ui.FeelingsApp
import app.feelings.tracker.ui.theme.FeelingsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as App).container
        setContent {
            val language = container.prefs.language.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM).value
            val theme = container.prefs.theme.collectAsStateWithLifecycle(
                initialValue = app.feelings.tracker.domain.model.ThemePref.SYSTEM,
            ).value
            LaunchedEffect(language) {
                val locales = when (language) {
                    AppLanguage.PL -> LocaleListCompat.forLanguageTags("pl")
                    AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
                    AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
                }
                if (AppCompatDelegate.getApplicationLocales() != locales) {
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }
            FeelingsTheme(theme = theme) {
                FeelingsApp(container)
            }
        }
    }
}
