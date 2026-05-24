package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.geodesy.UTMConverter
import com.example.rinex.SatObservation
import com.example.service.GnssForegroundService
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var gnssService: GnssForegroundService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GnssForegroundService.LocalBinder
            gnssService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            gnssService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Bind to GNSS core hardware listener service
        val intent = Intent(this, GnssForegroundService::class.java)
        startService(intent) // ensures service keeps running even when uncoupled
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme(darkTheme = true) { // Dark Mode high precision surveyor interface
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Sleek slate space depth background
                ) {
                    MainScreen(gnssService = gnssService, onShareFile = { file -> shareDatasetFile(file) })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun shareDatasetFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "com.aistudio.gnssrover.gnsrv.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Base RINEX GNSS Raw Survey Data: ${file.name}")
                putExtra(Intent.EXTRA_TEXT, "Adjunto archivo RINEX de observación cruda GNSS (.obs) para post-procesamiento diferencial.")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir Archivo RINEX"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error compartiendo archivo: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    gnssService: GnssForegroundService?,
    onShareFile: (File) -> Unit
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }

    // Required permissions checking array
    val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun checkLauncherPermissions() {
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Launch permission checking initially
    LaunchedEffect(Unit) {
        checkLauncherPermissions()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = @Suppress("KotlinConstantConditions") results.values.all { it }
        if (!permissionsGranted) {
            Toast.makeText(context, "Se necesitan permisos de ubicación precisa para acceder al GNSS crudo.", Toast.LENGTH_LONG).show()
        }
    }

    if (!permissionsGranted) {
        // High polish Permission Onboarding layout
        PermissionOnboarding(onRequestPermissions = {
            permissionLauncher.launch(requiredPermissions)
        })
    } else {
        if (gnssService == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF3B82F6))
            }
        } else {
            SurveyDashboard(gnssService = gnssService, onShareFile = onShareFile)
        }
    }
}

@Composable
fun PermissionOnboarding(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = "GPS Hardware Signal",
            tint = Color(0xFF60A5FA),
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFF1E293B), CircleShape)
                .padding(16.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Acceso a Datos Crudos GNSS",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "Esta estación de geodesia requiere permisos de ubicación precisa para interconectarse directamente con las seudodistancias físicas y fase de portadora provistas por el procesador del dispositivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = "Habilitar Conexión de Hardware",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurveyDashboard(
    gnssService: GnssForegroundService,
    onShareFile: (File) -> Unit
) {
    // Collect services states with lifecycle compatibility
    val isLogging by gnssService.isLogging.collectAsStateWithLifecycle()
    val elapsedSeconds by gnssService.elapsedSeconds.collectAsStateWithLifecycle()
    val location by gnssService.currentLocation.collectAsStateWithLifecycle()
    val utmCoordinate by gnssService.utmCoordinate.collectAsStateWithLifecycle()
    val satelliteMetrics by gnssService.satelliteMetrics.collectAsStateWithLifecycle()
    val geoidSeparation by gnssService.nmeaGeoidSeparation.collectAsStateWithLifecycle()
    val statusMessage by gnssService.statusMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Survey Field Inputs
    var markerName by remember { mutableStateOf("ROVER_001") }
    var observerName by remember { mutableStateOf("Ing_Geodesta") }
    var agencyName by remember { mutableStateOf("Estudio_Topografica_Base") }

    // Dynamic timer format (MM:SS)
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timerText = String.format(Locale.US, "%02d:%02d", minutes, seconds)

    // Layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // TOP LOGO BAR (HUD)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isLogging) Color(0xFF10B981) else Color(0xFFEF4444))
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "GNSS ROVER WORKSTATION",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.5.sp,
                color = Color(0xFF94A3B8),
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = if (isLogging) "EPOCHS: ${gnssService.rinexLogger.getEpochCount()}" else "LOG PASIVO",
                fontSize = 11.sp,
                color = if (isLogging) Color(0xFF10B981) else Color(0xFF94A3B8),
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(Color(0xFF1E293B), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Divider(color = Color(0xFF334155), thickness = 1.dp, modifier = Modifier.padding(bottom = 12.dp))

        // PARTE DE ENTRADA DE DATOS DEL PROYECTO (MARKER ETC.)
        AnimatedVisibility(visible = !isLogging) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "CONFIGURACIÓN DE CABECERA RINEX",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF60A5FA),
                        modifier = Modifier.padding(bottom = 8.dp),
                        fontFamily = FontFamily.Monospace
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = markerName,
                            onValueChange = { markerName = it },
                            label = { Text("ID Estacion", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF475569)
                            )
                        )
                        OutlinedTextField(
                            value = observerName,
                            onValueChange = { observerName = it },
                            label = { Text("Operador", fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF475569)
                            )
                        )
                    }
                }
            }
        }

        // CONTROL TIMING Y CONTADOR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // STOPWATCH CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .height(84.dp)
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("TIEMPO SESIÓN", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = timerText,
                        color = if (isLogging) Color(0xFF34D399) else Color(0xFFF3F4F6),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // PRIMARY BIG ACTION BUTTON
            Button(
                onClick = {
                    if (isLogging) {
                        gnssService.stopLogging()
                    } else {
                        gnssService.startLogging(markerName, observerName, agencyName)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLogging) Color(0xFFDC2626) else Color(0xFF059669)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.8f)
                    .height(84.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isLogging) Icons.Default.PlayArrow else Icons.Default.Refresh,
                        contentDescription = "Capture state button",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (isLogging) "PAUSAR\nLEVANTAMIENTO" else "INICIAR LOG\nDE CAMPO",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        color = Color.White
                    )
                }
            }
        }

        // COORDINATES UTM TELEMETRY (MAIN DISPLAY)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, Color(0xFF2563EB), RoundedCornerShape(16.dp))
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "UTM Geodetic Point",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "COORDENADAS UTM (WGS84)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF60A5FA),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Easting Coordinate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Easting (X):", color = Color(0xFF94A3B8), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = if (utmCoordinate != null) String.format(Locale.US, "%,10.3f m", utmCoordinate!!.easting) else "--- --- --- --",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Northing Coordinate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Northing (Y):", color = Color(0xFF94A3B8), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = if (utmCoordinate != null) String.format(Locale.US, "%,10.3f m", utmCoordinate!!.northing) else "--- --- --- --",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0xFF1E293B))
                Spacer(modifier = Modifier.height(8.dp))

                // UTM Metadata grid (Zone, Hemisphere, Convergencia)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("ZONA / HUSO", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = if (utmCoordinate != null) "${utmCoordinate!!.zone}${utmCoordinate!!.hemisphere}" else "S/N",
                            color = Color(0xFFFFB020),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("HEMISFERIO", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = if (utmCoordinate != null) (if (utmCoordinate!!.hemisphere == 'N') "NORTE" else "SUR") else "---",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("CONVERGENCIA", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = if (utmCoordinate != null) String.format(Locale.US, "%+1.4fº", utmCoordinate!!.convergence) else "0.0000º",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // ALTIMETRIC CARD (ELLIPSOID HEIGHT PURE)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "COTAS Y ALTIMETRÍA GEODÉSICA (WGS84)",
                    color = Color(0xFFA78BFA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Pure Ellipsoidal Height (h) - Preserves all 6 decimals as requested!
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Elevación Elipsoidal (h):", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                    Text(
                        text = if (location != null) String.format(Locale.US, "%.6f m", location!!.altitude) else "0.000000 m",
                        color = Color(0xFFC084FC),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Geoid Separation Undulation (N)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Separación Geoidal (N):", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                    Text(
                        text = if (geoidSeparation != null) String.format(Locale.US, "%.3f m", geoidSeparation) else "Sin NMEA GPS",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Ortometric estimation Altitude H = h - N
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Altura Ortométrica (H):", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                    val ortho = if (location != null && geoidSeparation != null) {
                        location!!.altitude - geoidSeparation!!
                    } else null
                    Text(
                        text = if (ortho != null) String.format(Locale.US, "%.3f m", ortho) else "Precisión Métrica",
                        color = Color(0xFFF472B6),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // INTERFACE STATUS BAR MESSAGE
        Text(
            text = "STATUS: $statusMessage",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                .padding(6.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // CONSTELACIONES DE SATÉLITES DETECTADAS (LIVE SPACE TERMINAL GRID)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "SEÑALES SATELITALES REGISTRADAS",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                if (satelliteMetrics.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Esperando mediciones crudas GNSS...\n(Asegúrese de estar al aire libre)",
                            color = Color(0xFF475569),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    // List of actively tracking Satellites in view
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(satelliteMetrics) { sat ->
                            SatelliteRow(sat = sat)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // EXPORT Y SHARE BUTTONS SECCION (ACTIVATED WHEN FILES ARE LOGGED)
        Button(
            onClick = {
                val file = gnssService.exportRinexFile()
                if (file != null && file.exists()) {
                    onShareFile(file)
                } else {
                    Toast.makeText(context, "No hay épocas guardadas para exportar. ¡Inicie sesión de campo!", Toast.LENGTH_LONG).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share RINEX export",
                    tint = Color.White
                )
                Text(
                    text = "EXPORTAR / COMPARTIR ARCHIVO RINEX 3.03",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun SatelliteRow(sat: SatObservation) {
    // Get style color for each system
    val color = when (sat.constellation) {
        'G' -> Color(0xFF60A5FA) // GPS Blue
        'R' -> Color(0xFFF87171) // GLONASS Red
        'E' -> Color(0xFFFFD700) // Galileo Gold
        'C' -> Color(0xFF34D399) // BeiDou Emerald
        else -> Color.White
    }

    val systemName = when (sat.constellation) {
        'G' -> "GPS"
        'R' -> "GLONASS"
        'E' -> "GALILEO"
        'C' -> "BEIDOU"
        else -> "MIX"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Constellation code + SVID
        Text(
            text = sat.prnString,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(48.dp)
        )

        // System descriptor
        Text(
            text = systemName,
            fontSize = 11.sp,
            color = Color(0xFF94A3B8),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(68.dp)
        )

        // Carrier frequency flag (L1 vs L5)
        Text(
            text = if (sat.isL5) "L5/E5" else "L1/E1/B1",
            fontSize = 11.sp,
            color = if (sat.isL5) Color(0xFFA78BFA) else Color(0xFF64748B),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(68.dp)
        )

        // SNR Progress visualizer
        Spacer(modifier = Modifier.weight(0.1f))
        val rawSnr = sat.snr?.toFloat() ?: 0.0f
        val snrNormal = (rawSnr / 50f).coerceIn(0.0f, 1.0f)

        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF0F172A))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(snrNormal)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(color.copy(alpha = 0.5f), color)
                        )
                    )
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Precise DbHz SNR text
        Text(
            text = String.format(Locale.US, "%2.1f dB-Hz", sat.snr ?: 0.0),
            fontSize = 11.sp,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp),
            textAlign = TextAlign.End
        )
    }
}
