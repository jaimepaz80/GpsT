package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.geodesy.UTMConverter
import com.example.rinex.RinexEpoch
import com.example.rinex.RinexLogger
import com.example.rinex.SatObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.io.FileOutputStream
import java.io.IOException

class GnssForegroundService : Service() {

    private val TAG = "GnssForegroundService"
    private val CHANNEL_ID = "GnssServiceChannel"
    private val NOTIFICATION_ID = 4242

    // Bound Service Binder
    inner class LocalBinder : Binder() {
        fun getService(): GnssForegroundService = this@GnssForegroundService
    }
    private val binder = LocalBinder()

    // Core Android location and power objects
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Logger instance
    val rinexLogger = RinexLogger()

    // Coroutine Scope for background work (timers, file save operations)
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var timerJob: Job? = null

    // Real-Time Observable States (M3 UI binds to these)
    private val _isLogging = MutableStateFlow(false)
    val isLogging = _isLogging.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds = _elapsedSeconds.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation = _currentLocation.asStateFlow()

    private val _utmCoordinate = MutableStateFlow<UTMConverter.UTMResult?>(null)
    val utmCoordinate = _utmCoordinate.asStateFlow()

    private val _satelliteMetrics = MutableStateFlow<List<SatObservation>>(emptyList())
    val satelliteMetrics = _satelliteMetrics.asStateFlow()

    private val _nmeaGeoidSeparation = MutableStateFlow<Double?>(null)
    val nmeaGeoidSeparation = _nmeaGeoidSeparation.asStateFlow()

    private val _statusMessage = MutableStateFlow("Iniciando servicio...")
    val statusMessage = _statusMessage.asStateFlow()

    private var activeSatsCount = IntArray(4) // GPS, GLONASS, Galileo, BeiDou counters

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service with location type
        startForeground(NOTIFICATION_ID, buildNotification("Buscando señal GNSS..."), 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun startLogging(marker: String, observer: String, agency: String) {
        if (_isLogging.value) return

        _isLogging.value = true
        _elapsedSeconds.value = 0
        _statusMessage.value = "Calibrando antena... registrando épocas."

        // Set surveyor details
        rinexLogger.clear()
        rinexLogger.markerName = marker.ifEmpty { "RoverStation" }
        rinexLogger.observerName = observer.ifEmpty { "AI Surveyor" }
        rinexLogger.agencyName = agency.ifEmpty { "Geotechnical Lab" }

        // Acquire WakeLock to keep GPS hardware tracking with screen off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GnssRover::SurveyWakeLock").apply {
            acquire()
        }

        // Register hardware callbacks
        try {
            // GNSS Raw Measurements
            locationManager?.registerGnssMeasurementsCallback(
                gnssMeasurementsCallback,
                Handler(Looper.getMainLooper())
            )

            // High-rate coordinates updates (Every second)
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0.0f,
                locationListener
            )

            // NMEA strings parsed to extract precise Geoidal undulation (GGA sentence)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager?.addNmeaListener(nmeaListener, Handler(Looper.getMainLooper()))
            }

            _statusMessage.value = "Levantamiento GNSS activo..."
        } catch (e: SecurityException) {
            _statusMessage.value = "Error: Sin permisos de Ubicación."
            stopLogging()
        } catch (e: Exception) {
            _statusMessage.value = "Error de Hardware GNSS: ${e.localizedMessage}"
            stopLogging()
        }

        // Launch survey clock timer (Ticks every second)
        timerJob = serviceScope.launch {
            while (_isLogging.value) {
                kotlinx.coroutines.delay(1000L)
                _elapsedSeconds.value += 1
                updateNotification()
            }
        }
    }

    fun stopLogging() {
        if (!_isLogging.value) return

        _isLogging.value = false
        timerJob?.cancel()
        timerJob = null

        // Release WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        // Unregister hardware callbacks
        try {
            locationManager?.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
            locationManager?.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager?.removeNmeaListener(nmeaListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unregister error: ${e.message}")
        }

        _statusMessage.value = "Levantamiento pausado. Archivo listo para exportar."
        updateNotification()
    }

    // Callbacks definitions
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _currentLocation.value = location
            
            // Raw latitude and longitude
            val lat = location.latitude
            val lon = location.longitude
            
            // In Android, getAltitude is referenced to WGS84 ellipsoid
            // We adjust it if we parsed the Geoid separation from NMEA GGA
            val geoidSep = _nmeaGeoidSeparation.value ?: 0.0
            val ellipsoidalHeight = location.altitude // Pure ellipsoidal

            // Perform premium geodetic projection to UTM coordinates
            val utm = UTMConverter.convertLatLonToUTM(lat, lon)
            _utmCoordinate.value = utm

            // Accumulate positions for approximate center in RINEX
            rinexLogger.addApproxPosition(lat, lon, ellipsoidalHeight)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
    }

    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            if (!_isLogging.value) return

            val clock = eventArgs.clock
            val measurements = eventArgs.measurements

            // High accuracy Epoch Time: We prefer using atomic GPS Time if fully synchronized.
            // Under GPS full bias condition, GPS nanoseconds since epoch translates into UTC millisecond time.
            var epochTimeMillis = System.currentTimeMillis()
            if (clock.hasFullBiasNanos()) {
                val gpsTimeNanos = clock.timeNanos - (clock.fullBiasNanos + if (clock.hasBiasNanos()) clock.biasNanos else 0.0)
                // Convert to UNIX time approximation: Leap seconds might be isolated,
                // but for post-processing alignment, matching UTC epoch with general millis is standard.
                epochTimeMillis = (gpsTimeNanos * 1e-6).toLong()
            }

            val satObsList = mutableListOf<SatObservation>()
            val counters = IntArray(4) // GPS, GLONASS, Galileo, BeiDou counters for UI view

            for (measurement in measurements) {
                val constellationType = measurement.constellationType
                val svid = measurement.svid
                val snr = measurement.cn0DbHz // CNR/SNR

                val constellationChar = when (constellationType) {
                    GnssStatus.CONSTELLATION_GPS -> {
                        counters[0]++
                        'G'
                    }
                    GnssStatus.CONSTELLATION_GLONASS -> {
                        counters[1]++
                        'R'
                    }
                    GnssStatus.CONSTELLATION_GALILEO -> {
                        counters[2]++
                        'E'
                    }
                    GnssStatus.CONSTELLATION_BEIDOU -> {
                        counters[3]++
                        'C'
                    }
                    else -> continue // Skip other systems
                }

                val hasFreq = measurement.hasCarrierFrequencyHz()
                val freqHz = if (hasFreq) measurement.carrierFrequencyHz else 0.0f
                val isL5 = hasFreq && (freqHz in 1.15e9f..1.25e9f)

                // High-precision pseudorange calculation
                var pseudorange: Double? = null
                if (clock.hasFullBiasNanos()) {
                    val gpsTimeNanos = clock.timeNanos - (clock.fullBiasNanos + if (clock.hasBiasNanos()) clock.biasNanos else 0.0)
                    val svTimeNanos = measurement.receivedSvTimeNanos.toDouble()

                    // Week wrapping GPS timescale standard
                    val WEEK_NANOS = 604800_000_000_000.0
                    val rxTimeWeek = gpsTimeNanos % WEEK_NANOS
                    var tof = rxTimeWeek - svTimeNanos
                    
                    if (tof < 0) {
                        tof += WEEK_NANOS
                    } else if (tof > WEEK_NANOS / 2.0) {
                        tof -= WEEK_NANOS
                    }

                    // Constrain reasonable transmission range (roughly 0.06 to 0.12 seconds flight duration)
                    val tofSec = tof * 1e-9
                    if (tofSec in 0.01..0.15) {
                        pseudorange = tofSec * 299792458.0
                    }
                }

                // Carrier Phase: Integrated delta values translated to wavelength units
                var phase: Double? = null
                val adrState = measurement.accumulatedDeltaRangeState
                val isAdrValid = (adrState and GnssMeasurement.ADR_STATE_VALID) != 0
                if (isAdrValid) {
                    val adrMeters = measurement.accumulatedDeltaRangeMeters
                    if (hasFreq && freqHz > 0) {
                        val lambda = 299792458.0 / freqHz
                        phase = adrMeters / lambda
                    } else {
                        // Standard GPS L1 lambda
                        phase = adrMeters / 0.19029367
                    }
                }

                // Doppler rate estimation
                val rate = measurement.pseudorangeRateMetersPerSecond
                val doppler = if (!rate.isNaN()) {
                    if (hasFreq && freqHz > 0) {
                        -rate / (299792458.0 / freqHz)
                    } else {
                        -rate / 0.19029367
                    }
                } else null

                // Slip detection
                var lli = 0
                if ((adrState and GnssMeasurement.ADR_STATE_CYCLE_SLIP) != 0) {
                    lli = 1
                }

                satObsList.add(
                    SatObservation(
                        constellation = constellationChar,
                        svid = svid,
                        isL5 = isL5,
                        pseudorange = pseudorange,
                        phase = phase,
                        doppler = doppler,
                        snr = snr,
                        lli = lli
                    )
                )
            }

            if (satObsList.isNotEmpty()) {
                val epoch = RinexEpoch(
                    utcTimeMillis = epochTimeMillis,
                    observations = satObsList
                )
                rinexLogger.addEpoch(epoch)
                
                activeSatsCount = counters
                _satelliteMetrics.value = satObsList
            }
        }
    }

    // NMEA listener to decouple Geoid separation (Undulation NMEA GGA)
    private val nmeaListener = object : OnNmeaMessageListener {
        override fun onNmeaMessage(message: String?, timestamp: Long) {
            if (message == null || !message.startsWith("$")) return
            
            // Split GGA string
            // $--GGA,hhmmss.ss,llll.ll,a,yyyyy.yy,a,x,xx,x.x,a.a,M,g.g,M,x.x,xxxx*hh
            // We isolate field indices: 9 (Orthm elevation), 11 (Geoid separation), 10 and 12 ('M' for meters)
            if (message.contains("GGA")) {
                try {
                    val parts = message.split(",")
                    if (parts.size >= 12 && parts[11].isNotEmpty()) {
                        val geoidSep = parts[11].toDouble()
                        _nmeaGeoidSeparation.value = geoidSep
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
            }
        }
    }

    // Save and retrieve Rinex text
    fun exportRinexFile(): File? {
        val content = rinexLogger.generateRinex3Content()
        if (content.isEmpty()) return null

        // Save inside the standard cache or localized app documents folder so FileProvider can map it securely.
        val dir = File(getExternalFilesDir(null), "rinex")
        if (!dir.exists()) dir.mkdirs()

        // Get actual year/obs extension (e.g., .26o for year 2026)
        val cal = java.util.Calendar.getInstance()
        val year2Digits = cal.get(java.util.Calendar.YEAR) % 100
        val filename = String.format("GNSS_ROVER_%03d%02d.obs", cal.get(java.util.Calendar.DAY_OF_YEAR), year2Digits)

        val file = File(dir, filename)
        return try {
            FileOutputStream(file).use { out ->
                out.write(content.toByteArray())
            }
            file
        } catch (e: IOException) {
            Log.e(TAG, "File creation error: ${e.message}")
            null
        }
    }

    // Safe Notification system
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal GNSS Rover",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Precisión GNSS Rover Colector")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val mins = _elapsedSeconds.value / 60
        val secs = _elapsedSeconds.value % 60
        val satSum = activeSatsCount.sum()
        val posMsg = if (_utmCoordinate.value != null) {
            val u = _utmCoordinate.value!!
            String.format(Locale.US, "Z:%d %s %1.2fe, %1.2fn", u.zone, u.hemisphere, u.easting, u.northing)
        } else {
            "Sats fijados: $satSum"
        }

        val text = "Tiempo: ${String.format("%02d:%02d", mins, secs)} | $posMsg"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogging()
        serviceJob.cancel()
    }
}
