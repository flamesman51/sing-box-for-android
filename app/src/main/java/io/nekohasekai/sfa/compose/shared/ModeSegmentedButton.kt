package io.nekohasekai.sfa.compose.shared

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MultiChoiceSegmentedButtonRowScope
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleChoiceSegmentedButtonRowScope.ModeSegmentedButton(
    index: Int,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    SegmentedButton(
        modifier = modifier,
        shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
        onClick = onClick,
        selected = selected,
        icon = {},
    ) {
        Text(label, maxLines = 1, softWrap = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiChoiceSegmentedButtonRowScope.ModeSegmentedButton(
    index: Int,
    count: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    SegmentedButton(
        modifier = modifier,
        shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
        onCheckedChange = onCheckedChange,
        checked = checked,
        icon = {},
    ) {
        Text(label, maxLines = 1, softWrap = false)
    }
}
