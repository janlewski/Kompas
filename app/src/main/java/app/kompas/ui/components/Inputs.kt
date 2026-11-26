package app.kompas.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun TileCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun RatingRow(
    labels: List<String>,
    descriptions: List<String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val value = index + 1
            val isSelected = selected == value
            val description = descriptions[index]
            Text(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = {
                        onSelect(if (isSelected) null else value)
                    })
                    .semantics { contentDescription = description }
                    .padding(4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
