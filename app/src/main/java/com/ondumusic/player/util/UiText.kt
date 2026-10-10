package com.ondumusic.player.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Text decided outside the UI, resolved against resources only when it's shown. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Raw -> text
    }

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Raw -> text
    }
}
