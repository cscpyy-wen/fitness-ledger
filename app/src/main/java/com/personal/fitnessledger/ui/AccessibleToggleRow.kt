package com.personal.fitnessledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class ToggleVisual { SWITCH, CHECKBOX }

/** One named action node for touch, keyboard, Switch Access and TalkBack.
 * The visual control has no independent callback, so it cannot become a second
 * anonymous action beside its sibling label. */
@Composable
internal fun LabeledToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    visual: ToggleVisual,
    testTag: String? = null,
) {
    val taggedModifier = if (testTag == null) modifier else modifier.testTag(testTag)
    Row(
        modifier = taggedModifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = if (visual == ToggleVisual.SWITCH) Role.Switch else Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (visual) {
            ToggleVisual.SWITCH -> Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            ToggleVisual.CHECKBOX -> Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(label)
            supportingText?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
