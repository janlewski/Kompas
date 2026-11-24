package app.feelings.tracker.ui.confirm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.feelings.tracker.App
import app.feelings.tracker.R
import app.feelings.tracker.domain.model.DoseSource
import app.feelings.tracker.domain.model.DoseStatus
import app.feelings.tracker.platform.notify.NotificationFactory
import app.feelings.tracker.ui.theme.FeelingsTheme
import kotlinx.coroutines.launch

class DoseConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as App).container
        val action = intent.getStringExtra(NotificationFactory.EXTRA_ACTION)
        val actionId = intent.getStringExtra(NotificationFactory.EXTRA_ACTION_ID).orEmpty()
        val medicationId = intent.getStringExtra(NotificationFactory.EXTRA_MEDICATION_ID).orEmpty()
        val occurrenceId = intent.getStringExtra(NotificationFactory.EXTRA_OCCURRENCE_ID)
        setContent {
            FeelingsTheme(theme = app.feelings.tracker.domain.model.ThemePref.SYSTEM) {
                val scope = rememberCoroutineScope()
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.confirm_body))
                    Button(onClick = {
                        scope.launch {
                            val status = if (action == NotificationFactory.ACTION_TAKEN) {
                                DoseStatus.TAKEN
                            } else {
                                DoseStatus.SKIPPED
                            }
                            container.recordDose.record(
                                actionId = actionId,
                                medicationId = medicationId,
                                occurrenceId = occurrenceId,
                                status = status,
                                actualAtUtc = container.clock.instant(),
                                zoneId = container.zones.zoneId(),
                                actualDoseAmount = null,
                                actualDoseUnit = null,
                                snoozeUntilUtc = null,
                                source = DoseSource.NOTIFICATION,
                                supersedesEventId = null,
                            )
                            finish()
                        }
                    }) {
                        Text(stringResource(R.string.confirm_dose))
                    }
                    Button(onClick = { finish() }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}
