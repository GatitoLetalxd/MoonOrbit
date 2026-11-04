package online.moondev.orbit

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.api.MoonOrbitWebSocket
import online.moondev.orbit.data.local.PreferencesManager
import online.moondev.orbit.service.LocationForegroundService
import online.moondev.orbit.theme.DarkBackground
import online.moondev.orbit.theme.MoonOrbitTheme
import online.moondev.orbit.ui.auth.LoginScreen
import online.moondev.orbit.ui.auth.RegisterScreen
import online.moondev.orbit.ui.group.AdminPendingScreen
import online.moondev.orbit.ui.group.PendingApprovalScreen
import online.moondev.orbit.ui.map.MapScreen
import online.moondev.orbit.ui.permissions.PermissionsScreen
import online.moondev.orbit.ui.profile.ProfileScreen

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private val api = MoonOrbitApi()
    private val ws = MoonOrbitWebSocket.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            MoonOrbitTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    val navController = rememberNavController()
                    val scope = rememberCoroutineScope()

                    var startDestination by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        prefs.serverUrl.collect { url ->
                            api.setBaseUrl(url)
                            ws.setWsUrl(PreferencesManager.httpToWsUrl(url))
                        }
                    }

                    LaunchedEffect(Unit) {
                        val token = prefs.sessionToken.firstOrNull()
                        val permissionsDone = prefs.permissionsCompleted.firstOrNull() ?: false
                        val status = prefs.status.firstOrNull()

                        if (token == null) {
                            startDestination = "login"
                        } else if (!permissionsDone) {
                            startDestination = "permissions"
                        } else if (status == "pending") {
                            startDestination = "pending"
                        } else {
                            startDestination = "map"
                            startLocationService()
                        }
                    }

                    if (startDestination != null) {
                        NavHost(
                            navController = navController,
                            startDestination = startDestination!!
                        ) {
                            composable("login") {
                                LoginScreen(
                                    api = api,
                                    prefs = prefs,
                                    onLoginSuccess = { status ->
                                        scope.launch {
                                            connectWebSocket()
                                            val permsDone = prefs.permissionsCompleted.firstOrNull() ?: false
                                            if (!permsDone) {
                                                navController.navigate("permissions") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                            } else if (status == "pending") {
                                                navController.navigate("pending") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                            } else {
                                                navController.navigate("map") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                                startLocationService()
                                            }
                                        }
                                    },
                                    onNavigateToRegister = {
                                        navController.navigate("register")
                                    }
                                )
                            }

                            composable("register") {
                                RegisterScreen(
                                    api = api,
                                    prefs = prefs,
                                    onRegisterSuccess = {
                                        navController.navigate("permissions") {
                                            popUpTo("register") { inclusive = true }
                                        }
                                    },
                                    onNavigateToLogin = {
                                        navController.popBackStack()
                                    }
                                )
                            }

                            composable("permissions") {
                                PermissionsScreen(
                                    onAllPermissionsGranted = {
                                        scope.launch {
                                            prefs.setPermissionsCompleted(true)
                                            val status = prefs.status.firstOrNull()
                                            if (status == "pending") {
                                                navController.navigate("pending") {
                                                    popUpTo("permissions") { inclusive = true }
                                                }
                                            } else {
                                                navController.navigate("map") {
                                                    popUpTo("permissions") { inclusive = true }
                                                }
                                                startLocationService()
                                            }
                                        }
                                    }
                                )
                            }

                            composable("pending") {
                                PendingApprovalScreen(
                                    api = api,
                                    ws = ws,
                                    prefs = prefs,
                                    onApproved = {
                                        connectWebSocket()
                                        navController.navigate("map") {
                                            popUpTo("pending") { inclusive = true }
                                        }
                                        startLocationService()
                                    },
                                    onLogout = {
                                        ws.disconnect()
                                        val stopIntent = Intent(this@MainActivity, LocationForegroundService::class.java).apply {
                                            action = LocationForegroundService.ACTION_STOP_SERVICE
                                        }
                                        startService(stopIntent)
                                        navController.navigate("login") {
                                            popUpTo("pending") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable("map") {
                                var currentGroupId by remember { mutableStateOf("") }
                                LaunchedEffect(Unit) {
                                    val gId = prefs.groupId.firstOrNull() ?: ""
                                    currentGroupId = gId
                                }

                                if (currentGroupId.isNotEmpty()) {
                                    MapScreen(
                                        api = api,
                                        ws = ws,
                                        prefs = prefs,
                                        groupId = currentGroupId,
                                        onNavigateToAdmin = {
                                            navController.navigate("admin")
                                        },
                                        onNavigateToProfile = {
                                            navController.navigate("profile")
                                        }
                                    )
                                }
                            }

                            composable("admin") {
                                var currentGroupId by remember { mutableStateOf("") }
                                LaunchedEffect(Unit) {
                                    currentGroupId = prefs.groupId.firstOrNull() ?: ""
                                }
                                if (currentGroupId.isNotEmpty()) {
                                    AdminPendingScreen(
                                        api = api,
                                        groupId = currentGroupId,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }

                            composable("profile") {
                                ProfileScreen(
                                    api = api,
                                    prefs = prefs,
                                    onBack = { navController.popBackStack() },
                                    onLogout = {
                                        ws.disconnect()
                                        val stopIntent = Intent(this@MainActivity, LocationForegroundService::class.java).apply {
                                            action = LocationForegroundService.ACTION_STOP_SERVICE
                                        }
                                        startService(stopIntent)
                                        navController.navigate("login") {
                                            popUpTo("map") { inclusive = true }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun connectWebSocket() {
        applicationScope.launch {
            val url = prefs.serverUrl.firstOrNull() ?: PreferencesManager.DEFAULT_SERVER_URL
            api.setBaseUrl(url)
            ws.setWsUrl(PreferencesManager.httpToWsUrl(url))
            val token = prefs.sessionToken.firstOrNull()
            if (token != null) {
                api.setAuthToken(token)
                ws.connect(token)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectWebSocket()
    }

    override fun onStop() {
        super.onStop()
        // WebSocket remains active through LocationForegroundService for background SOS alerts
    }

    private val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
}
