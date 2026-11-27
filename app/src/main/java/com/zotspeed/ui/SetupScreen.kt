package com.zotspeed.ui

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(vm: AppViewModel) {
    val busy by vm.setupBusy.collectAsStateWithLifecycle()
    val error by vm.setupError.collectAsStateWithLifecycle()
    val oauthClient by vm.oauthClient.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var clientKey by rememberSaveable { mutableStateOf(oauthClient.first) }
    var clientSecret by rememberSaveable { mutableStateOf(oauthClient.second) }
    var showOAuthApp by rememberSaveable { mutableStateOf(!vm.oauthBuiltIn && oauthClient.first.isBlank()) }
    val context = LocalContext.current
    val oauthReady = vm.oauthBuiltIn || (oauthClient.first.isNotBlank() && oauthClient.second.isNotBlank())

    fun open(url: String) {
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("ZotSpeed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Speed-read the EPUBs in your Zotero library. Sign in once (easiest on the main screen); " +
                "after that, everything works on the cover screen.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        // --- Sign in with Zotero (OAuth) ---
        Button(
            onClick = { vm.startOAuth(::open) },
            enabled = !busy && oauthReady,
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        ) { Text("Sign in with Zotero") }

        if (!vm.oauthBuiltIn) {
            TextButton(onClick = { showOAuthApp = !showOAuthApp }) {
                Text(if (showOAuthApp) "Hide OAuth app settings" else "OAuth app settings")
            }
            if (showOAuthApp) {
                Text(
                    "Sign-in needs an OAuth app registered at zotero.org/oauth/apps " +
                        "(callback URL: zotspeed://oauth). Paste its client key and secret here, " +
                        "or build them into the APK (see README).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
                TextButton(onClick = { open("https://www.zotero.org/oauth/apps") }) { Text("Register an OAuth app") }
                OutlinedTextField(
                    value = clientKey, onValueChange = { clientKey = it },
                    label = { Text("Client key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it },
                    label = { Text("Client secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                )
                OutlinedButton(
                    onClick = { vm.setOAuthClient(clientKey, clientSecret); showOAuthApp = false },
                    enabled = clientKey.isNotBlank() && clientSecret.isNotBlank(),
                ) { Text("Save") }
            }
        }

        // --- Or paste an API key ---
        Row(Modifier.widthIn(max = 480.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("  or use an API key  ", style = MaterialTheme.typography.labelSmall)
            HorizontalDivider(Modifier.weight(1f))
        }
        TextButton(onClick = { open("https://www.zotero.org/settings/keys/new") }) {
            Text("Create a key on zotero.org")
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Zotero API key") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.connect(key) }),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.connect(key) }, enabled = !busy && key.isNotBlank()) {
                Text("Connect with key")
            }
            if (busy) {
                Spacer(Modifier.size(12.dp))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}
