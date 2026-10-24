package com.example.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.FirestoreRepository
import com.example.ui.chat.ChatScreen
import com.example.ui.codebase.CodebaseScreen
import com.example.ui.library.ScriptLibraryScreen
import com.example.ui.playground.PlaygroundScreen
import com.example.ui.security.SecurityAuditScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

enum class AppDestination(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    STUDIO("AI Studio", Icons.Default.ChatBubble),
    CODEBASE("Codebase", Icons.Default.Folder),
    PLAYGROUND("Playground", Icons.Default.PlayArrow),
    SECURITY("Security", Icons.Default.Security),
    CLOUD("Cloud Scripts", Icons.Default.Cloud)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    repository: FirestoreRepository
) {
    var currentDestination by remember { mutableStateOf(AppDestination.STUDIO) }

    // Handle back button: if not on STUDIO, return to STUDIO
    BackHandler(enabled = currentDestination != AppDestination.STUDIO) {
        currentDestination = AppDestination.STUDIO
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_scaffold"),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_xcraft_badge),
                            contentDescription = "Xcraft Logo",
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Xcraft.py",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(EmeraldTertiary.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("v2.0 Sovereign", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = EmeraldTertiary)
                                }
                            }
                            Text(
                                text = "Python AI Framework • Zero Vulnerability",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                modifier = Modifier.testTag("bottom_navigation_bar")
            ) {
                AppDestination.entries.forEach { destination ->
                    val selected = currentDestination == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentDestination = destination },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = destination.title,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = CyanPrimary,
                            indicatorColor = CyanPrimary,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier.testTag("nav_tab_${destination.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentDestination) {
                AppDestination.STUDIO -> ChatScreen(repository = repository)
                AppDestination.CODEBASE -> CodebaseScreen()
                AppDestination.PLAYGROUND -> PlaygroundScreen(repository = repository)
                AppDestination.SECURITY -> SecurityAuditScreen(repository = repository)
                AppDestination.CLOUD -> ScriptLibraryScreen(
                    repository = repository,
                    onOpenInPlayground = { _, _ ->
                        currentDestination = AppDestination.PLAYGROUND
                    }
                )
            }
        }
    }
}
