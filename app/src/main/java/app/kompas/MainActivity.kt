package app.kompas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kompas.domain.model.AppLanguage
import app.kompas.ui.KompasApp
import app.kompas.ui.theme.KompasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as App).container
        setContent {
            val language = container.prefs.language.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM).value
            val theme = container.prefs.theme.collectAsStateWithLifecycle(
                initialValue = app.kompas.domain.model.ThemePref.SYSTEM,
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
            KompasTheme(theme = theme) {
                KompasApp(container)
            }
        }
    }
}
