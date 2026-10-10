package com.ondumusic.player.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ondumusic.player.R
import com.ondumusic.player.auth.AuthService
import com.ondumusic.player.auth.ServerAddress
import com.ondumusic.player.ui.components.AppIcon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private const val SERVER_SETUP_URL = "https://ondumusic.com/setup.html"

@Composable
fun LoginScreen(auth: AuthService) {
    val isLoading by auth.isLoading.collectAsStateWithLifecycle()
    val error by auth.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var server by rememberSaveable { mutableStateOf(auth.storedServerUrl?.let(ServerAddress::display).orEmpty()) }
    var username by rememberSaveable { mutableStateOf(auth.storedUsername.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val resolved = remember(server) { ServerAddress.resolve(server) }
    // Explains a rejected address, rather than leaving Sign In disabled with no indication why.
    val serverProblem = server.trim().takeIf { it.isNotEmpty() && resolved == null && it.lowercase().startsWith("http://") }
    val canSubmit = resolved != null && username.isNotEmpty() && password.isNotEmpty() && !isLoading

    val serverFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    fun submit() {
        val url = resolved ?: return
        if (!canSubmit) return
        scope.launch { auth.login(url, username.trim(), password) }
    }

    LaunchedEffect(Unit) {
        when {
            server.isEmpty() -> serverFocus.requestFocus()
            username.isEmpty() -> usernameFocus.requestFocus()
            else -> passwordFocus.requestFocus()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(size = 64.dp)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; auth.clearError() },
                    label = { Text(stringResource(R.string.login_server)) },
                    placeholder = { Text("music.example.com") },
                    leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    singleLine = true,
                    isError = serverProblem != null,
                    supportingText = serverProblem?.let { { Text(stringResource(R.string.login_https_required)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { usernameFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(serverFocus),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; auth.clearError() },
                    label = { Text(stringResource(R.string.login_username)) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus).semantics { contentType = ContentType.Username },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; auth.clearError() },
                    label = { Text(stringResource(R.string.login_password)) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).semantics { contentType = ContentType.Password },
                )

                error?.let { message ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(message.resolve(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    // Overlaid rather than swapped, so the button doesn't change size mid-submit.
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.login_sign_in), Modifier.alpha(if (isLoading) 0f else 1f))
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        }
                    }
                }
                TextButton(onClick = { uriHandler.openUri(SERVER_SETUP_URL) }) {
                    Text(stringResource(R.string.login_no_server))
                }
            }
        }
    }
}
