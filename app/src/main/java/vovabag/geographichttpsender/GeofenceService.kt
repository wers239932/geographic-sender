package vovabag.geographichttpsender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import vovabag.geographichttpsender.data.SettingsRepository
import vovabag.geographichttpsender.model.TargetPoint
import vovabag.geographichttpsender.network.HttpClient
import vovabag.geographichttpsender.util.GeoUtils
import kotlin.math.min

private const val TAG = "GeofenceService"

class GeofenceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val httpClient = HttpClient()
    private val repository by lazy { SettingsRepository(this) }

    private var targetPoints = emptyList<TargetPoint>()
    private var locationCallback: LocationCallback? = null
    private val lastRequestTime = mutableMapOf<String, Long>()
    private val lastRequestStatus = mutableMapOf<String, String>()
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentUpdateIntervalMs = FAR_INTERVAL_MS

    companion object {
        const val CHANNEL_ID = "geofence_service_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_TARGET_POINTS = "extra_target_points"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_POINTS = "ACTION_UPDATE_POINTS"

        // Адаптивные интервалы обновления (миллисекунды)
        private const val FAR_INTERVAL_MS = 120000L    // 2 мин — далеко (>1 км от всех активных точек)
        private const val MID_INTERVAL_MS = 20000L     // 20 сек — средне (>600 м, но <1 км)
        private const val NEAR_INTERVAL_MS = 10000L    // 10 сек — близко (<600 м)

        // Пороговые расстояния
        private const val FAR_DISTANCE = 1000f    // 1 км
        private const val MID_DISTANCE = 600f     // 600 м
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GeographicSender::LocationWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L /* 10 min, will be re-acquired on location update */)
        }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val pointsJson = intent.getStringExtra(EXTRA_TARGET_POINTS)
                if (!pointsJson.isNullOrEmpty()) {
                    targetPoints = parseTargetPoints(pointsJson)
                    resetStatuses()
                }
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                serviceScope.launch { repository.setServiceRunning(true) }
                startLocationUpdates()
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                serviceScope.launch { repository.setServiceRunning(false) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_POINTS -> {
                val pointsJson = intent.getStringExtra(EXTRA_TARGET_POINTS)
                if (!pointsJson.isNullOrEmpty()) {
                    targetPoints = parseTargetPoints(pointsJson)
                    resetStatuses()
                    lastRequestStatus.keys.retainAll(targetPoints.map { it.id }.toSet())
                    lastRequestTime.keys.retainAll(targetPoints.map { it.id }.toSet())
                }
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        serviceScope.launch { repository.setServiceRunning(false) }
        serviceScope.cancel()
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service when task is swiped away
        val restartIntent = Intent(this, GeofenceService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_TARGET_POINTS, com.google.gson.Gson().toJson(targetPoints))
        }
        startForegroundService(restartIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun startLocationUpdates() {
        requestLocationUpdatesWithInterval(FAR_INTERVAL_MS)
    }

    private fun requestLocationUpdatesWithInterval(intervalMs: Long) {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMs
        ).apply {
            setMinUpdateIntervalMillis(intervalMs / 2)
            // Wait for accurate location to avoid unnecessary callbacks
            if (intervalMs <= NEAR_INTERVAL_MS) {
                setMaxUpdateDelayMillis(intervalMs * 2)
            } else {
                setMaxUpdateDelayMillis(intervalMs)
            }
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    refreshWakeLock()
                    checkProximityToPoints(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            updatePointStatus("", "Нет разрешения на геолокацию")
        }
    }

    private fun checkProximityToPoints(userLocation: Location) {
        val currentTime = System.currentTimeMillis()
        var minDistance = Float.MAX_VALUE
        val activePoints = targetPoints.filter { it.isEnabled }

        // Если нет активных точек — используем максимальный интервал
        if (activePoints.isEmpty()) {
            switchIntervalIfNeeded(FAR_INTERVAL_MS)
            return
        }

        activePoints.forEach { point ->
            val distance = GeoUtils.calculateDistance(
                userLocation.latitude, userLocation.longitude,
                point.latitude, point.longitude
            )

            minDistance = min(minDistance, distance)

            Log.d(TAG, "${point.name}: расстояние=${distance.toInt()}м, радиус=${point.triggerRadius.toInt()}м")

            if (distance > point.triggerRadius) {
                Log.d(TAG, "  ${point.name}: далеко (${distance.toInt()}м > ${point.triggerRadius.toInt()}м)")
                updatePointStatus(point.id, "Далеко (${distance.toInt()}м)")
                return@forEach
            }

            point.directionFilter?.let { filter ->
                val userBearing = userLocation.bearing
                if (!GeoUtils.isBearingWithinTolerance(userBearing, filter.bearing, filter.tolerance)) {
                    Log.d(TAG, "  ${point.name}: неверное направление (${userBearing.toInt()}° vs ${filter.bearing.toInt()}°+/-${filter.tolerance.toInt()}°)")
                    updatePointStatus(point.id, "Неверное направление")
                    return@forEach
                }
            }

            point.speedThreshold?.let { maxSpeed ->
                val userSpeed = userLocation.speed
                if (userSpeed > maxSpeed) {
                    Log.d(TAG, "  ${point.name}: слишком быстро (${userSpeed.toInt()} м/с > ${maxSpeed.toInt()} м/с)")
                    updatePointStatus(point.id, "Слишком быстро (${userSpeed.toInt()} м/с)")
                    return@forEach
                }
            }

            val lastTime = lastRequestTime[point.id] ?: 0L
            val intervalMs = point.httpConfig.intervalSeconds * 1000
            if (currentTime - lastTime < intervalMs) {
                val remaining = (intervalMs - (currentTime - lastTime)) / 1000
                updatePointStatus(point.id, "Следующий через ${remaining}с")
                return@forEach
            }

            Log.d(TAG, "  ${point.name}: ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ, отправка запроса!")
            lastRequestTime[point.id] = currentTime
            sendHttpRequest(point)
        }

        // Адаптивная смена интервала в зависимости от расстояния до ближайшей активной точки
        val newInterval = when {
            minDistance < MID_DISTANCE -> NEAR_INTERVAL_MS      // < 600 м → каждые 10 сек
            minDistance < FAR_DISTANCE -> MID_INTERVAL_MS       // < 1 км → каждые 20 сек
            else -> FAR_INTERVAL_MS                             // > 1 км → каждые 2 мин
        }

        switchIntervalIfNeeded(newInterval)
    }

    private fun switchIntervalIfNeeded(newInterval: Long) {
        if (newInterval != currentUpdateIntervalMs) {
            Log.d(TAG, "Смена интервала: ${currentUpdateIntervalMs}мс -> ${newInterval}мс")
            currentUpdateIntervalMs = newInterval
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
                requestLocationUpdatesWithInterval(currentUpdateIntervalMs)
            }
        }
    }

    private fun sendHttpRequest(point: TargetPoint) {
        serviceScope.launch {
            updatePointStatus(point.id, "Отправка...")
            val result = httpClient.sendRequest(point.httpConfig)
            result.onSuccess {
                updatePointStatus(point.id, "Отправлено")
            }.onFailure { error ->
                updatePointStatus(point.id, "Ошибка: ${error.message?.take(30)}")
            }
        }
    }

    private fun updatePointStatus(id: String, status: String) {
        lastRequestStatus[id] = status
        updateNotification()
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Geofence Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activeCount = targetPoints.count { it.isEnabled }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Geographic HTTP Sender")
            .setContentText("Отслеживание активно ($activeCount из ${targetPoints.size} точек)")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val activePoints = targetPoints.filter { it.isEnabled }
        val statusText = activePoints.joinToString("\n") { point ->
            "${point.name.ifBlank { point.id.take(8) }}: ${lastRequestStatus[point.id] ?: "Ожидание..."}"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Geographic HTTP Sender")
            .setContentText("Активно: ${activePoints.size} из ${targetPoints.size} точек")
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseTargetPoints(pointsJson: String): List<TargetPoint> {
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<List<TargetPoint>>() {}.type
        return gson.fromJson(pointsJson, type)
    }

    private fun resetStatuses() {
        val activeStatuses = lastRequestStatus.filterKeys { id -> targetPoints.any { it.id == id } }.toMutableMap()
        targetPoints.forEach { point ->
            activeStatuses.putIfAbsent(point.id, if (point.isEnabled) "Ожидание..." else "Отключена")
        }
        lastRequestStatus.clear()
        lastRequestStatus.putAll(activeStatuses)
    }
}