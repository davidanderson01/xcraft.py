package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.FirestoreRepository
import com.example.domain.telemetry.ExternalSessionTelemetryBridge
import com.example.ui.auth.AuthScreen
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    private lateinit var telemetryBridge: ExternalSessionTelemetryBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            com.google.firebase.FirebaseApp.initializeApp(applicationContext)
        } catch (e: Exception) {
            // Ignored if already initialized
        }
        telemetryBridge = ExternalSessionTelemetryBridge.getInstance(applicationContext)
        telemetryBridge.attachSession(intent)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    AppContent(telemetryBridge = telemetryBridge)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        telemetryBridge.attachSession(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!telemetryBridge.sessionState.value.isActive) {
            telemetryBridge.attachSession(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            telemetryBridge.notifyWindowClosedAsync("ACTIVITY_FINISHED")
        }
    }

    override fun onDestroy() {
        telemetryBridge.notifyWindowClosedAsync("WINDOW_CLOSED")
        super.onDestroy()
    }
}

@Composable
fun AppContent(
    telemetryBridge: ExternalSessionTelemetryBridge = ExternalSessionTelemetryBridge.getInstance(LocalContext.current)
) {
    val context = LocalContext.current
    var isGuestMode by remember { mutableStateOf(false) }
    val sessionState by telemetryBridge.sessionState.collectAsStateWithLifecycle()

    var currentUser by remember {
        mutableStateOf(
            try {
                Firebase.auth.currentUser
            } catch (e: Exception) {
                null
            }
        )
    }

    // Observe Firebase auth changes
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        try {
            Firebase.auth.addAuthStateListener(listener)
        } catch (e: Exception) {
            // Handled if auth provider is offline
        }
        onDispose {
            try {
                Firebase.auth.removeAuthStateListener(listener)
            } catch (e: Exception) {
                // Cleanup safely
            }
        }
    }

    if (currentUser == null && !isGuestMode) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = Firebase.auth.currentUser
            },
            onContinueAsGuest = {
                isGuestMode = true
            }
        )
    } else {
        val repository = remember { FirestoreRepository.create(context) }
        AppNavigation(
            repository = repository,
            initialSummonedTitle = sessionState.summonedScriptTitle,
            initialSummonedCode = sessionState.summonedScriptCode
        )
    }
}
