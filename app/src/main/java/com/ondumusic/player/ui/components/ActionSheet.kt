package com.ondumusic.player.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One entry in an [ActionSheet]. Groups are separated by dividers, the way iOS context menus are. */
data class Action(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The Android counterpart of an iOS context menu: a bottom sheet headed by what it acts on.
 * Opened from a row's overflow button or a long press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String,
    subtitle: String?,
    groups: List<List<Action>>,
    onDismiss: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            ListItem(
                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                leadingContent = leading,
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            groups.filter { it.isNotEmpty() }.forEach { group ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                group.forEach { action ->
                    val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    ListItem(
                        headlineContent = { Text(action.label, color = tint) },
                        leadingContent = { Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow {
                            // Close first, so whatever the action opens isn't stacked under a closing sheet.
                            scope.launch { state.hide() }.invokeOnCompletion {
                                onDismiss()
                                action.onClick()
                            }
                        },
                    )
                }
            }
        }
    }
}
