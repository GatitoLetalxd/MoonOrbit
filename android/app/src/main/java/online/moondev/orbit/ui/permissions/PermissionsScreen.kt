package online.moondev.orbit.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.moondev.orbit.theme.*

enum class PermissionStep(
    val stepNumber: Int,
    val title: String,
    val subtitle: String,
    val description: String,
    val guideInstructions: String,
    val icon: ImageVector,
    val iconColor: Color
) {
    PRECISE_LOCATION(
        stepNumber = 1,
        title = "Ubicación Precisa",
        subtitle = "Paso 1 de 4",
        description = "Permite a tu grupo familiar ver tu ubicación en tiempo real en el mapa con máxima exactitud.",
        guideInstructions = "Selecciona 'Mientras la app está en uso' y activa la opción 'Ubicación precisa' en la ventana que aparecerá.",
        icon = Icons.Default.LocationOn,
        iconColor = DarkPrimary
    ),
    BACKGROUND_LOCATION(
        stepNumber = 2,
        title = "Ubicación en Segundo Plano",
        subtitle = "Paso 2 de 4",
        description = "Permite que la app siga compartiendo tu posición de forma segura cuando la pantalla esté apagada o uses otras apps.",
        guideInstructions = "En la pantalla de ajustes de permisos de Android, selecciona 'Permitir todo el tiempo'.",
        icon = Icons.Default.NearMe,
        iconColor = DarkTertiary
    ),
    NOTIFICATIONS(
        stepNumber = 3,
        title = "Notificación de Estado",
        subtitle = "Paso 3 de 4",
        description = "Android exige una notificación permanente para que siempre tengas el control y sepas que la ubicación está activa.",
        guideInstructions = "Toca 'Permitir' para habilitar las alertas y la barra de estado del servicio familiar.",
        icon = Icons.Default.NotificationsActive,
        iconColor = DarkSuccess
    ),
    BATTERY_OPTIMIZATION(
        stepNumber = 4,
        title = "Ahorro de Batería",
        subtitle = "Paso 4 de 4",
        description = "Los fabricantes (Samsung, Xiaomi, Huawei) cierran aplicaciones en segundo plano si no se configura 'Sin restricciones'.",
        guideInstructions = "Permite que MoonOrbit funcione sin optimización de batería agresiva para evitar desconexiones falsas.",
        icon = Icons.Default.BatteryChargingFull,
        iconColor = DarkWarning
    )
}

@Composable
fun PermissionsScreen(
    onAllPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current

    fun determineFirstMissingStep(): PermissionStep {
        val fineLocationGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted) return PermissionStep.PRECISE_LOCATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!bgGranted) return PermissionStep.BACKGROUND_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!notifGranted) return PermissionStep.NOTIFICATIONS
        }

        return PermissionStep.BATTERY_OPTIMIZATION
    }

    var currentStep by remember { mutableStateOf(determineFirstMissingStep()) }

    // Launcher for Location
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            currentStep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PermissionStep.BACKGROUND_LOCATION
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionStep.NOTIFICATIONS
            } else {
                PermissionStep.BATTERY_OPTIMIZATION
            }
        }
    }

    // Launcher for Background Location
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            currentStep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionStep.NOTIFICATIONS
            } else {
                PermissionStep.BATTERY_OPTIMIZATION
            }
        }
    }

    // Launcher for Notifications
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        currentStep = PermissionStep.BATTERY_OPTIMIZATION
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        }
        openAppSettings()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text(
                    text = "Configuración de Permisos",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = DarkTextPrimary,
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "MoonOrbit necesita estos permisos para garantizar que tu familia pueda cuidarte en todo momento.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = DarkTextSecondary),
                    textAlign = TextAlign.Center
                )
            }

            // Animated step card
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "PermissionCard"
            ) { step ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .border(1.dp, DarkBorder, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Step badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = step.subtitle,
                                color = step.iconColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Glowing Icon Container
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            step.iconColor.copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .border(2.dp, step.iconColor.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = step.icon,
                                contentDescription = null,
                                tint = step.iconColor,
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = DarkTextPrimary,
                                fontWeight = FontWeight.Bold
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = step.description,
                            style = MaterialTheme.typography.bodyMedium.copy(color = DarkTextSecondary),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Step guide box
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = step.iconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = step.guideInstructions,
                                    style = MaterialTheme.typography.bodySmall.copy(color = DarkTextPrimary),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Button(
                    onClick = {
                        when (currentStep) {
                            PermissionStep.PRECISE_LOCATION -> {
                                locationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                            PermissionStep.BACKGROUND_LOCATION -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } else {
                                    currentStep = PermissionStep.BATTERY_OPTIMIZATION
                                }
                            }
                            PermissionStep.NOTIFICATIONS -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    currentStep = PermissionStep.BATTERY_OPTIMIZATION
                                }
                            }
                            PermissionStep.BATTERY_OPTIMIZATION -> {
                                openBatterySettings()
                                onAllPermissionsGranted()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = currentStep.iconColor,
                        contentColor = DarkBackground
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = when (currentStep) {
                            PermissionStep.PRECISE_LOCATION -> "Conceder permiso de ubicación"
                            PermissionStep.BACKGROUND_LOCATION -> "Permitir todo el tiempo"
                            PermissionStep.NOTIFICATIONS -> "Permitir notificaciones"
                            PermissionStep.BATTERY_OPTIMIZATION -> "Desactivar ahorro de batería"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Secondary Settings Fallback Button
                OutlinedButton(
                    onClick = {
                        if (currentStep == PermissionStep.BATTERY_OPTIMIZATION) {
                            openBatterySettings()
                        } else {
                            openAppSettings()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkTextSecondary),
                    border = BorderStroke(1.dp, DarkBorder),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Abrir ajustes de la app",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (currentStep == PermissionStep.BATTERY_OPTIMIZATION) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onAllPermissionsGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Omitir y continuar al mapa",
                            color = DarkTextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
