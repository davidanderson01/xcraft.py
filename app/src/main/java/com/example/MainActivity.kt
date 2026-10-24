package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import com.example.data.repository.FirestoreRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            com.google.firebase.FirebaseApp.initializeApp(applicationContext)
        } catch (e: Exception) {
            // Ignored if already initialized
        }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    AppContent()
                }
            }
        }
    }
}

@Composable
fun AppContent() {
    val context = LocalContext.current
    var isGuestMode by remember { mutableStateOf(false) }

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
        AppNavigation(repository = repository)
    }
}
