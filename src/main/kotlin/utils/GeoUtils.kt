package utils

import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calculate the distance between two points on Earth using the Haversine formula.
     * @return distance in kilometers
     */
    fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Check if a point is within a given radius from a center point.
     */
    fun isWithinRadius(
        centerLat: Double, centerLng: Double,
        pointLat: Double, pointLng: Double,
        radiusKm: Double
    ): Boolean {
        return haversineDistance(centerLat, centerLng, pointLat, pointLng) <= radiusKm
    }
}
