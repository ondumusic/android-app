package com.ondumusic.player.ui.banners

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.settings.SignOutDialog
import kotlinx.coroutines.launch

/**
 * Asks for the password again when the server stops accepting the session. Never signs
 * out on its own: that would delete the library and downloads over what may be a hiccup.
 */
@Composable
fun ReAuthDialog(onDismiss: () -> Unit) {
    val auth = LocalContainer.current.auth
    val error by auth.error.collectAsStateWithLifecycle()
    val loading by auth.isLoading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmSignOut by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        auth.clearError()
        focus.requestFocus()
    }

    fun submit() {
        if (password.isEmpty() || loading) return
        scope.launch {
            if (auth.reAuthenticate(password)) onDismiss() else password = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.reauth_title)) },
        text = {
            Column {
                auth.storedUsername?.let { Text(stringResource(R.string.reauth_message, it), Modifier.padding(bottom = 16.dp)) }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it.resolve()) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentType = ContentType.Password },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = password.isNotEmpty() && !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                else Text(stringResource(R.string.login_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmSignOut = true }) {
                Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
            }
        },
    )
    if (confirmSignOut) SignOutDialog(onDismiss = { confirmSignOut = false })
}
