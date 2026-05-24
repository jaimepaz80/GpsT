package com.example.geodesy

import kotlin.math.*

/**
 * High-precision geodesic converter for converting WGS84 coordinates (Latitude, Longitude)
 * to Universal Transverse Mercator (UTM) coordinates using the rigorous ellipsoidal earth formulas.
 * 
 * This implementation avoids flat-earth or spherical simplifications, preserving sub-millimeter
 * mathematical resolution of the Double types delivered by modern surveyor instruments.
 */
object UTMConverter {

    // WGS84 Ellipsoid constants
    private const val A = 6378137.0         // semi-major axis (meters)
    private const val F = 1.0 / 298.257223563 // flattening
    private const val B = A * (1.0 - F)      // semi-minor axis = 6356752.314245
    private val E2 = (A * A - B * B) / (A * A) // first eccentricity squared (~ 0.00669437999014)
    private val EP2 = (A * A - B * B) / (B * B) // second eccentricity squared
    
    private const val K0 = 0.9996 // UTM scale factor

    data class UTMResult(
        val easting: Double,
        val northing: Double,
        val zone: Int,
        val hemisphere: Char, // 'N' or 'S'
        val convergence: Double // Grid convergence angle in degrees (optional diagnostic)
    )

    /**
     * Converts WGS84 geodetic latitude and longitude to UTM coordinates.
     * Coordinate values are kept strictly as 64-bit precision Double floating points.
     *
     * @param lat Latitude in decimal degrees
     * @param lon Longitude in decimal degrees
     * @return UTMResult containing high-fidelity projected Coordinates
     */
    fun convertLatLonToUTM(lat: Double, lon: Double): UTMResult {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)

        // Determine UTM Zone
        var zone = ((lon + 180.0) / 6.0).toInt() + 1
        if (zone > 60) zone = 60
        if (zone < 1) zone = 1

        // Adjust for specific UTM Zone anomalies (Norway/Svalbard exception if needed)
        // General formula handles all zones standardly.
        
        // Central meridian of the zone
        val lon0 = ((zone - 1) * 6.0 - 180.0 + 3.0)
        val lon0Rad = Math.toRadians(lon0)

        val dLon = lonRad - lon0Rad

        // Central meridian longitudinal difference normalization (-PI to PI)
        val dLonNorm = atan2(sin(dLon), cos(dLon))

        val cosLat = cos(latRad)
        val sinLat = sin(latRad)
        val tanLat = tan(latRad)
        val cosLat2 = cosLat * cosLat
        val tanLat2 = tanLat * tanLat

        // Radius of curvature in prime vertical
        val nu = A / sqrt(1.0 - E2 * sinLat * sinLat)

        // Calculating Meridian Distance (M)
        val m = calculateMeridianDistance(latRad)

        // TM projection expansion terms
        val a1 = dLonNorm * cosLat
        val a1_2 = a1 * a1
        val a1_3 = a1_2 * a1
        val a1_4 = a1_3 * a1
        val a1_5 = a1_4 * a1
        val a1_6 = a1_5 * a1

        val ep2CosLat2 = EP2 * cosLat2

        // Easting calculation (relative to central meridian with 500k false easting offset)
        val xTerm1 = a1
        val xTerm2 = (1.0 - tanLat2 + ep2CosLat2) * a1_3 / 6.0
        val xTerm3 = (5.0 - 18.0 * tanLat2 + tanLat2 * tanLat2 + 72.0 * ep2CosLat2 - 58.0 * EP2) * a1_5 / 120.0
        
        val easting = K0 * nu * (xTerm1 + xTerm2 + xTerm3) + 500000.0

        // Northing calculation (relative to equator with 10M false northing for south hemisphere)
        val yTerm1 = a1_2 / 2.0
        val yTerm2 = (5.0 - tanLat2 + 9.0 * ep2CosLat2 + 4.0 * ep2CosLat2 * ep2CosLat2) * a1_4 / 24.0
        val yTerm3 = (61.0 - 58.0 * tanLat2 + tanLat2 * tanLat2 * tanLat2 + 600.0 * ep2CosLat2 - 330.0 * EP2) * a1_6 / 720.0

        var northing = K0 * (m + nu * tanLat * (yTerm1 + yTerm2 + yTerm3))
        
        val hemisphere = if (lat >= 0) 'N' else 'S'
        if (hemisphere == 'S') {
            northing += 10000000.0 // Add False Northing for Southern Hemisphere
        }

        // Grid convergence (meridian convergence) gamma
        val convergence = Math.toDegrees(sinLat * dLonNorm * (1.0 + (1.0 + 3.0 * ep2CosLat2) * a1_2 / 3.0))

        return UTMResult(easting, northing, zone, hemisphere, convergence)
    }

    /**
     * Compute meridian distance (S) from equator to latitude phi.
     * This uses premium ellipsoidal integrals mapping latitude to distance accurately.
     */
    private fun calculateMeridianDistance(phi: Double): Double {
        // Coefficients for the meridian length series
        val alpha = A * (1.0 - E2 / 4.0 - 3.0 * E2 * E2 / 64.0 - 5.0 * E2 * E2 * E2 / 256.0)
        val beta = A * (3.0 * E2 / 8.0 + 3.0 * E2 * E2 / 32.0 + 45.0 * E2 * E2 * E2 / 1024.0)
        val gamma = A * (15.0 * E2 * E2 / 256.0 + 45.0 * E2 * E2 * E2 / 1024.0)
        val delta = A * (35.0 * E2 * E2 * E2 / 3072.0)

        return alpha * phi - beta * sin(2.0 * phi) + gamma * sin(4.0 * phi) - delta * sin(6.0 * phi)
    }
}
