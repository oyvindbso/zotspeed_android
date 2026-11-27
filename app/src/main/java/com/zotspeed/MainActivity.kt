package com.zotspeed

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zotspeed.data.ZoteroOAuth
import com.zotspeed.ui.AppViewModel
import com.zotspeed.ui.LibraryScreen
import com.zotspeed.ui.ReaderKey
import com.zotspeed.ui.ReaderScreen
import com.zotspeed.ui.Screen
import com.zotspeed.ui.SetupScreen
import com.zotspeed.ui.theme.ZotSpeedTheme
import com.zotspeed.ui.theme.isDark

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == ZoteroOAuth.CALLBACK_SCHEME && uri.host == ZoteroOAuth.CALLBACK_HOST) {
            vm.handleOAuthCallback(uri.getQueryParameter("oauth_token"), uri.getQueryParameter("oauth_verifier"))
        }
    }

    @Composable
    private fun App() {
        val screen by vm.screen.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val dark = isDark(settings.theme, isSystemInDarkTheme())

        LaunchedEffect(dark) {
            val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        LaunchedEffect(settings.showOverLockScreen) {
            // Lets the reader be used on the cover screen without unlocking the phone.
            setShowWhenLocked(settings.showOverLockScreen)
        }

        ZotSpeedTheme(settings.theme) {
            BackHandler(enabled = screen == Screen.READER) { vm.onBack() }
            when (screen) {
                Screen.SETUP -> SetupScreen(vm)
                Screen.LIBRARY -> LibraryScreen(vm)
                Screen.READER -> ReaderScreen(vm)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SPACE -> ReaderKey.NEXT
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> ReaderKey.PREVIOUS
            else -> null
        }
        if (key != null && vm.onKey(key)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow the matching key-up so the volume panel doesn't appear while reading.
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            vm.screen.value == Screen.READER && vm.settings.value.volumeKeys
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        vm.persistPosition()
        super.onStop()
    }
}
