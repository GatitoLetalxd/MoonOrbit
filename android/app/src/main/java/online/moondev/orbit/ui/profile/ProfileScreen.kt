package online.moondev.orbit.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.moondev.orbit.data.api.ActiveSessionDto
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.local.PreferencesManager
import online.moondev.orbit.theme.*
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    api: MoonOrbitApi,
    prefs: PreferencesManager,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var avatarVersion by remember { mutableStateOf(0L) }
    var isSaving by remember { mutableStateOf(false) }
    var isUploadingAvatar by remember { mutableStateOf(false) }
    var localPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeSessions by remember { mutableStateOf<List<ActiveSessionDto>>(emptyList()) }
    var isLoadingSessions by remember { mutableStateOf(false) }

    fun loadSessions() {
        isLoadingSessions = true
        scope.launch {
            try {
                activeSessions = api.getActiveSessions()
            } catch (e: Exception) {}
            isLoadingSessions = false
        }
    }

    LaunchedEffect(Unit) {
        loadSessions()
    }

    LaunchedEffect(Unit) {
        prefs.userId.collectLatest { if (it != null) userId = it }
    }
    LaunchedEffect(Unit) {
        prefs.avatarVersion.collectLatest { avatarVersion = it }
    }
    LaunchedEffect(Unit) {
        prefs.username.collectLatest { if (it != null) username = it }
    }
    LaunchedEffect(Unit) {
        prefs.displayName.collectLatest { if (it != null) displayName = it }
    }
    LaunchedEffect(Unit) {
        prefs.phone.collectLatest { if (it != null) phone = it }
    }
    LaunchedEffect(Unit) {
        prefs.role.collectLatest { if (it != null) role = it }
    }
    LaunchedEffect(Unit) {
        prefs.groupName.collectLatest { if (it != null) groupName = it }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isUploadingAvatar = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val originalBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()

                        val maxDim = 512
                        val width = originalBitmap.width
                        val height = originalBitmap.height
                        val scale = (maxDim.toFloat() / maxOf(width, height)).coerceAtMost(1f)
                        val scaledBitmap = Bitmap.createScaledBitmap(
                            originalBitmap,
                            (width * scale).toInt(),
                            (height * scale).toInt(),
                            true
                        )

                        localPreviewBitmap = scaledBitmap

                        val outputStream = ByteArrayOutputStream()
                        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                        scaledBitmap.compress(format, 80, outputStream)
                        outputStream.toByteArray()
                    }

                    val newVersion = api.uploadAvatar(bytes)
                    prefs.updateAvatarVersion(newVersion)
                    avatarVersion = newVersion
                    Toast.makeText(context, "Foto de perfil actualizada", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Error al actualizar avatar: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isUploadingAvatar = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mi Perfil", color = DarkTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = DarkTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar Circle (Clickable with photo picker)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(DarkPrimary.copy(alpha = 0.2f))
                    .border(2.dp, DarkPrimary, CircleShape)
                    .clickable {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (localPreviewBitmap != null) {
                    Image(
                        bitmap = localPreviewBitmap!!.asImageBitmap(),
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (avatarVersion > 0 && userId.isNotEmpty()) {
                    AsyncImage(
                        model = api.getAvatarUrl(userId, avatarVersion),
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = displayName.take(1).uppercase().ifEmpty { "U" },
                        color = DarkPrimary,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (isUploadingAvatar) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = DarkPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(DarkPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Cambiar avatar",
                            tint = DarkBackground,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Toca para cambiar foto",
                color = DarkTextSecondary,
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = DarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = "@$username • ${if (role == "admin") "Administrador" else "Miembro"}",
                style = MaterialTheme.typography.bodyMedium.copy(color = DarkTextSecondary)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Profile info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Datos de Contacto",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = DarkTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Nombre visible") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DarkPrimary,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = DarkTextPrimary,
                            unfocusedTextColor = DarkTextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Teléfono (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DarkPrimary,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = DarkTextPrimary,
                            unfocusedTextColor = DarkTextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            isSaving = true
                            scope.launch {
                                try {
                                    api.updateProfile(
                                        displayName = displayName.trim(),
                                        phone = phone.trim().ifEmpty { null }
                                    )
                                    Toast.makeText(context, "Perfil guardado con éxito", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkPrimary, contentColor = DarkBackground),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Guardar Cambios", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connected Devices / Active Sessions Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, contentDescription = null, tint = DarkPrimary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Dispositivos Conectados",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = DarkTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        IconButton(onClick = { loadSessions() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recargar", tint = DarkPrimary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Text(
                        text = "Sesiones activas en tu cuenta (máximo 5 permitidas)",
                        style = MaterialTheme.typography.bodySmall.copy(color = DarkTextSecondary)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (activeSessions.isEmpty()) {
                        Text("Cargando sesiones...", color = DarkTextSecondary, fontSize = 13.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            activeSessions.forEach { sess ->
                                Surface(
                                    color = if (sess.isCurrent) DarkPrimary.copy(alpha = 0.08f) else DarkSurfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                    border = if (sess.isCurrent) androidx.compose.foundation.BorderStroke(1.dp, DarkPrimary.copy(alpha = 0.3f)) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                Icons.Default.PhoneAndroid,
                                                contentDescription = null,
                                                tint = if (sess.isCurrent) DarkPrimary else DarkTextSecondary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = sess.deviceName.ifBlank { "Dispositivo Android" },
                                                        color = DarkTextPrimary,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 14.sp
                                                    )
                                                    if (sess.isCurrent) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(
                                                            color = DarkPrimary.copy(alpha = 0.2f),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                text = "ESTE TELÉFONO",
                                                                color = DarkPrimary,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = "Activo: ${sess.lastUsedAt.take(10)}",
                                                    color = DarkTextSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        if (!sess.isCurrent) {
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        try {
                                                            api.revokeSession(sess.id)
                                                            loadSessions()
                                                            Toast.makeText(context, "Sesión cerrada", Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Revocar", tint = DarkError, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (activeSessions.size > 1) {
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            api.logoutOthers()
                                            loadSessions()
                                            Toast.makeText(context, "Se cerró la sesión en los demás teléfonos", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkWarning),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkWarning.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cerrar sesión en los demás dispositivos", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Logout Button
            Button(
                onClick = {
                    scope.launch {
                        try { api.logout() } catch (e: Exception) {}
                        prefs.clearSession()
                        onLogout()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkError.copy(alpha = 0.15f), contentColor = DarkError),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cerrar Sesión", fontWeight = FontWeight.Bold)
            }
        }
    }
}
