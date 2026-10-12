package com.ondumusic.player.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import com.ondumusic.player.R
import com.ondumusic.player.ui.components.ScreenScaffold

/**
 * The libraries built into the app and their licenses, whose terms ask for the notice to ship
 * with every copy. The list is generated at build time from the dependencies, so it can't
 * fall out of date.
 */
@Composable
fun LicensesScreen() {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    ScreenScaffold(stringResource(R.string.settings_licenses), showBack = true) { padding ->
        LibrariesContainer(
            libraries,
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            // The bundled text, not the license's web page: it has to be readable offline too.
            detailMode = LibraryDetailMode.Sheet,
        )
    }
}
