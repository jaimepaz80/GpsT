package com.example.rinex

import android.os.Build
import com.example.geodesy.UTMConverter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Model class representing a single satellite observation at an epoch.
 */
data class SatObservation(
    val constellation: Char, // 'G' = GPS, 'R' = GLONASS, 'E' = Galileo, 'C' = BeiDou
    val svid: Int,          // Space Vehicle ID
    val isL5: Boolean,       // True if frequency corresponds to L5/E5 (approx 1.176GHz)
    val pseudorange: Double?, // C1C or C5X (meters)
    val phase: Double?,       // L1C or L5X (cycles, AccumulatedDeltaRange)
    val doppler: Double?,     // D1C or D5X (Hz, pseudorange rate converted to doppler or raw doppler)
    val snr: Double?,         // S1C or S5X (dB-Hz)
    val lli: Int = 0          // Loss of Lock Indicator
) {
    val prnString: String get() = String.format(Locale.US, "%c%02d", constellation, svid)
}

/**
 * Model class representing a logged epoch in RINEX format.
 */
data class RinexEpoch(
    val utcTimeMillis: Long,
    val receiverClockOffsetSec: Double = 0.0,
    val observations: List<SatObservation>
)

/**
 * RinexLogger is a thread-safe high-precision compiler that processes and formats GPS time,
 * GnssClock and GnssMeasurement coordinates, and observation arrays into a standard RINEX 3.03
 * surveyor file. All mathematical and formatting rules follow the IGS RINEX 3.x specification.
 */
class RinexLogger(
    var markerName: String = "RoverStation",
    var observerName: String = "AI Surveyor",
    var agencyName: String = "Geotechnical Lab"
) {
    // List of epochs, thread-safe
    private val epochs = mutableListOf<RinexEpoch>()

    // Approximate geodetic positions updated during the survey
    private var approxLat = 0.0
    private var approxLon = 0.0
    private var approxH = 0.0
    private var sampleCount = 0

    @Synchronized
    fun clear() {
        epochs.clear()
        approxLat = 0.0
        approxLon = 0.0
        approxH = 0.0
        sampleCount = 0
    }

    @Synchronized
    fun addApproxPosition(lat: Double, lon: Double, h: Double) {
        if (lat != 0.0 && lon != 0.0) {
            approxLat = (approxLat * sampleCount + lat) / (sampleCount + 1)
            approxLon = (approxLon * sampleCount + lon) / (sampleCount + 1)
            approxH = (approxH * sampleCount + h) / (sampleCount + 1)
            sampleCount++
        }
    }

    @Synchronized
    fun addEpoch(epoch: RinexEpoch) {
        epochs.add(epoch)
    }

    @Synchronized
    fun getEpochCount(): Int = epochs.size

    @Synchronized
    fun generateRinex3Content(): String {
        val sb = StringBuilder()
        val utcFormat = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val creationDateStr = utcFormat.format(Date()) + " UTC"

        // 1. HEADER section (Must be 80 characters wide per line)
        sb.append(formatLine("     3.03           OBSERVATION DATA    M (MIXED)           ", "RINEX VERSION / TYPE"))
        sb.append(formatLine(
            String.format(Locale.US, "%-20s%-20s%-20s", limitStr(observerName, 20), limitStr(agencyName, 20), creationDateStr),
            "PGM / RUN BY / DATE"
        ))
        sb.append(formatLine(String.format(Locale.US, "%-20s%-20s%-20s", "GNSS Rover App", "ANDROID ROVER", ""), "COMMENT"))
        sb.append(formatLine(String.format(Locale.US, "%-60s", limitStr(markerName, 60)), "MARKER NAME"))
        sb.append(formatLine(String.format(Locale.US, "%-60s", "10001S001"), "MARKER NUMBER"))
        sb.append(formatLine(String.format(Locale.US, "%-20s%-20s%-20s", "Android Device", Build.MANUFACTURER, Build.MODEL), "REC # / TYPE / VERS"))
        sb.append(formatLine(String.format(Locale.US, "%-20s%-20s%-20s", "Internal", "INTERNAL_ANT", ""), "ANT # / TYPE"))

        // Compute Cartesian XYZ of WGS84 Geodetic point for RINEX APPR POSITION card
        val xyz = convertGeodeticToXYZ(approxLat, approxLon, approxH)
        sb.append(formatLine(
            String.format(Locale.US, " %13.4f %13.4f %13.4f", xyz[0], xyz[1], xyz[2]),
            "APPROX POSITION XYZ"
        ))
        sb.append(formatLine("        0.0000        0.0000        0.0000                  ", "ANTENNA: DELTA H/E/N"))

        // Observation types per constellation
        // GPS (G) and Galileo (E) can provide Dual-frequency L1 & L5
        sb.append(formatLine("G    8 C1C L1C D1C S1C C5X L5X D5X S5X                      ", "SYS / # / OBS TYPES"))
        sb.append(formatLine("E    8 C1C L1C D1C S1C C5X L5X D5X S5X                      ", "SYS / # / OBS TYPES"))
        // GLONASS (R) and BeiDou (C) are usually single frequency in standard android logs
        sb.append(formatLine("R    4 C1C L1C D1C S1C                                      ", "SYS / # / OBS TYPES"))
        sb.append(formatLine("C    4 C2I L2I D2I S2I                                      ", "SYS / # / OBS TYPES"))

        sb.append(formatLine("  1.0000                                                    ", "INTERVAL"))

        // Time of first observation
        if (epochs.isNotEmpty()) {
            val firstEpoch = epochs.first()
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = firstEpoch.utcTimeMillis
            }
            val startStr = String.format(
                Locale.US,
                "  %4d    %2d    %2d    %2d    %2d   %11.7f     GPS      ",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND) + (cal.get(java.util.Calendar.MILLISECOND) / 1000.0)
            )
            sb.append(formatLine(startStr, "TIME OF FIRST OBS"))
        }

        sb.append(formatLine("                                                            ", "END OF HEADER"))

        // 2. OBSERVATIONS EPOCH LOGS
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        for (epoch in epochs) {
            cal.timeInMillis = epoch.utcTimeMillis
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = cal.get(java.util.Calendar.MINUTE)
            val second = cal.get(java.util.Calendar.SECOND) + (cal.get(java.util.Calendar.MILLISECOND) / 1000.0)

            // Filter supported epochs and group
            val activeSats = epoch.observations.filter {
                it.constellation == 'G' || it.constellation == 'R' || it.constellation == 'E' || it.constellation == 'C'
            }
            if (activeSats.isEmpty()) continue

            // Epoch header line, e.g.
            // > 2026 05 23 13 15 30.0000000  0 12
            // flag 0 = OK, activeSats.size = number of satellites, receiver clock offset is appended if desired
            sb.append(String.format(
                Locale.US,
                "> %4d %02d %02d %02d %02d %11.7f  0 %2d\n",
                year, month, day, hour, minute, second, activeSats.size
            ))

            for (sat in activeSats) {
                // Formatting depending on Constellation designators
                val satLine = when (sat.constellation) {
                    'G', 'E' -> {
                        // GPS and Galileo: 8 fields (L1: C1C L1C D1C S1C, L5: C5X L5X D5X S5X)
                        val p1Val = if (!sat.isL5) sat.pseudorange else null
                        val l1Val = if (!sat.isL5) sat.phase else null
                        val d1Val = if (!sat.isL5) sat.doppler else null
                        val s1Val = if (!sat.isL5) sat.snr else null

                        val p5Val = if (sat.isL5) sat.pseudorange else null
                        val l5Val = if (sat.isL5) sat.phase else null
                        val d5Val = if (sat.isL5) sat.doppler else null
                        val s5Val = if (sat.isL5) sat.snr else null

                        sat.prnString +
                                formatObservation(p1Val, sat.lli) +
                                formatObservation(l1Val, sat.lli) +
                                formatObservation(d1Val) +
                                formatObservation(s1Val) +
                                formatObservation(p5Val, sat.lli) +
                                formatObservation(l5Val, sat.lli) +
                                formatObservation(d5Val) +
                                formatObservation(s5Val)
                    }
                    'R' -> {
                        // GLONASS: 4 fields (L1 C1C L1C D1C S1C)
                        sat.prnString +
                                formatObservation(sat.pseudorange, sat.lli) +
                                formatObservation(sat.phase, sat.lli) +
                                formatObservation(sat.doppler) +
                                formatObservation(sat.snr)
                    }
                    'C' -> {
                        // BeiDou: 4 fields B1 (C2I L2I D2I S2I)
                        sat.prnString +
                                formatObservation(sat.pseudorange, sat.lli) +
                                formatObservation(sat.phase, sat.lli) +
                                formatObservation(sat.doppler) +
                                formatObservation(sat.snr)
                    }
                    else -> ""
                }
                if (satLine.isNotEmpty()) {
                    sb.append(satLine.trimEnd()).append("\n")
                }
            }
        }

        return sb.toString()
    }

    /**
     * Helper to pad label fields strictly to 80 characters wide per line standard.
     */
    private fun formatLine(headerData: String, label: String): String {
        val paddedData = String.format(Locale.US, "%-60s", headerData)
        val paddedLabel = String.format(Locale.US, "%-20s", label)
        return (paddedData + paddedLabel).substring(0, 80) + "\n"
    }

    private fun limitStr(str: String, limit: Int): String {
        return if (str.length > limit) str.substring(0, limit) else str
    }

    /**
     * Formats the observation float into column f14.3 + lli + ssi (Total 16 chars).
     */
    private fun formatObservation(value: Double?, lli: Int = 0, ssi: Int = 0): String {
        if (value == null || value == 0.0 || value.isNaN() || value.isInfinite()) {
            return "                " // 16 spaces
        }
        val formattedVal = String.format(Locale.US, "%14.3f", value)
        val lliChar = if (lli > 0) lli.toString() else " "
        val ssiChar = if (ssi > 0) ssi.toString() else " "
        return "$formattedVal$lliChar$ssiChar"
    }

    /**
     * Helper to perform high-precision geodetic to Cartesian projection (WGS84).
     */
    private fun convertGeodeticToXYZ(latDeg: Double, lonDeg: Double, h: Double): DoubleArray {
        if (latDeg == 0.0 && lonDeg == 0.0) {
            return doubleArrayOf(0.0, 0.0, 0.0)
        }
        val latRad = Math.toRadians(latDeg)
        val lonRad = Math.toRadians(lonDeg)

        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val e2 = 2.0 * f - f * f

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinLon = sin(lonRad)
        val cosLon = cos(lonRad)

        val n = a / sqrt(1.0 - e2 * sinLat * sinLat)

        val x = (n + h) * cosLat * cosLon
        val y = (n + h) * cosLat * sinLon
        val z = (n * (1.0 - e2) + h) * sinLat

        return doubleArrayOf(x, y, z)
    }
}
