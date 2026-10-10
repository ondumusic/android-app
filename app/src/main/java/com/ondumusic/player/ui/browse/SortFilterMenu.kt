package com.ondumusic.player.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ondumusic.player.R
import com.ondumusic.player.settings.LibraryFilter

/** One ordering in the menu. [direction] describes the current direction, and is shown only on the selected one. */
class SortChoice(val label: String, val selected: Boolean, val ascending: Boolean, val direction: String?, val onClick: () -> Unit)

/**
 * The library filter and the orderings behind one toolbar button. Choosing the selected
 * ordering again reverses it, as on iOS, so direction needs no controls of its own. With
 * no [choices] it's a filter alone, as on the Artists tab.
 */
@Composable
fun SortFilterMenu(filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit, choices: List<SortChoice>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            // A dot while the filter hides part of the library, so an emptier list than
            // expected has a visible reason.
            BadgedBox(badge = { if (filter == LibraryFilter.Downloaded) Badge() }) {
                if (choices.isEmpty()) {
                    Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.filter))
                } else {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.sort_and_filter))
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader(stringResource(R.string.filter_header))
            listOf(LibraryFilter.All to R.string.filter_all_music, LibraryFilter.Downloaded to R.string.filter_downloaded).forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { CheckSlot(filter == option) },
                    onClick = { open = false; onFilter(option) },
                )
            }
            if (choices.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuHeader(stringResource(R.string.sort_header))
            }
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.label)
                            if (choice.selected && choice.direction != null) {
                                Text(choice.direction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    leadingIcon = { CheckSlot(choice.selected) },
                    trailingIcon = if (choice.selected && choice.direction != null) {
                        { Icon(if (choice.ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward, contentDescription = null, Modifier.size(18.dp)) }
                    } else null,
                    onClick = { open = false; choice.onClick() },
                )
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

/** Always takes the room, so labels line up whether or not they're checked. */
@Composable
private fun CheckSlot(checked: Boolean) {
    Box(Modifier.size(24.dp)) {
        if (checked) Icon(Icons.Outlined.Check, contentDescription = null)
    }
}
