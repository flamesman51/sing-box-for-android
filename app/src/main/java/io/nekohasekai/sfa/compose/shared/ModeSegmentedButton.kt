package io.nekohasekai.sfa.compose.shared

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleChoiceSegmentedButtonRowScope.ModeSegmentedButton(
    index: Int,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    SegmentedButton(
        shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
        onClick = onClick,
        selected = selected,
    ) {
        Text(label)
    }
}
