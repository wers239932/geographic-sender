package vovabag.geographichttpsender.util

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    /**
     * Расстояние в метрах между двумя точками (формула гаверсинусов)
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val location1 = Location("").apply { latitude = lat1; longitude = lon1 }
        val location2 = Location("").apply { latitude = lat2; longitude = lon2 }
        return location1.distanceTo(location2)
    }

    /**
     * Пеленг (bearing) от точки 1 к точке 2 в градусах (0-360)
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lonDiff = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(lonDiff) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(lonDiff)
        var bearing = Math.toDegrees(atan2(y, x)).toFloat()
        if (bearing < 0) bearing += 360f
        return bearing
    }

    /**
     * Проверка, находится ли пеленг в пределах допуска
     */
    fun isBearingWithinTolerance(actualBearing: Float, targetBearing: Float, tolerance: Float): Boolean {
        val diff = kotlin.math.abs((actualBearing - targetBearing + 360f) % 360f)
        return diff <= tolerance || diff >= (360f - tolerance)
    }
}
