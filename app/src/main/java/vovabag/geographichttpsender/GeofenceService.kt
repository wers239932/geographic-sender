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
    private var wakeLock: PowerManager.WakeLock? = null

    // Статус отправки по каждой точке: SENDING, SENT, ERROR(message)
    private enum class SendStatus { SENDING, SENT, ERROR }
    private data class PointSendInfo(
        val status: SendStatus,
        val errorMessage: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val pointSendInfo = mutableMapOf<String, PointSendInfo>()

    // Последнее известное расстояние до каждой точки
    private val lastKnownDistance = mutableMapOf<String, Float>()

    private var currentUpdateIntervalMs = FAR_INTERVAL_MS
    private var notificationUpdateJob: Job? = null

    companion object {
        const val CHANNEL_ID = "geofence_service_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_TARGET_POINTS = "extra_target_points"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_POINTS = "ACTION_UPDATE_POINTS"

        // Адаптивные интервалы обновления (миллисекунды)
        private const val FAR_INTERVAL_MS = 120000L    // 2 мин — далеко (>1 км от всех активных точек)
        private const val MID_INTERVAL_MS = 20000L     // 20 сек — средне (>500 м, но <1 км)
        private const val NEAR_INTERVAL_MS = 1000L    // 1 сек — близко (<500 м)

        // Пороговые расстояния
        private const val FAR_DISTANCE = 1000f    // 1 км
        private const val MID_DISTANCE = 500f     // 500 м

        // Период обновления уведомления
        private const val NOTIFICATION_UPDATE_MS = 5000L
        // Статус отправки считается актуальным N секунд
        private const val STATUS_FRESHNESS_MS = 5000L
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
                    resetState()
                }
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                serviceScope.launch { repository.setServiceRunning(true) }
                startLocationUpdates()
                startNotificationUpdater()
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                stopNotificationUpdater()
                serviceScope.launch { repository.setServiceRunning(false) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_POINTS -> {
                val pointsJson = intent.getStringExtra(EXTRA_TARGET_POINTS)
                if (!pointsJson.isNullOrEmpty()) {
                    targetPoints = parseTargetPoints(pointsJson)
                    resetState()
                }
                updateNotificationNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        stopNotificationUpdater()
        serviceScope.launch { repository.setServiceRunning(false) }
        serviceScope.cancel()
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(this, GeofenceService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_TARGET_POINTS, com.google.gson.Gson().toJson(targetPoints))
        }
        startForegroundService(restartIntent)
        super.onTaskRemoved(rootIntent)
    }

    // ── Периодическое обновление уведомления ──

    private fun startNotificationUpdater() {
        stopNotificationUpdater()
        notificationUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_UPDATE_MS)
                updateNotificationNow()
            }
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun stopNotificationUpdater() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }

    // ── Location ──

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
            Log.e(TAG, "Нет разрешения на геолокацию")
        }
    }

    private fun checkProximityToPoints(userLocation: Location) {
        val currentTime = System.currentTimeMillis()
        var minDistance = Float.MAX_VALUE
        val activePoints = targetPoints.filter { it.isEnabled }

        if (activePoints.isEmpty()) {
            switchIntervalIfNeeded(FAR_INTERVAL_MS)
            return
        }

        activePoints.forEach { point ->
            val distance = GeoUtils.calculateDistance(
                userLocation.latitude, userLocation.longitude,
                point.latitude, point.longitude
            )

            lastKnownDistance[point.id] = distance
            minDistance = min(minDistance, distance)

            Log.d(TAG, "${point.name}: расстояние=${distance.toInt()}м, радиус=${point.triggerRadius.toInt()}м")

            if (distance > point.triggerRadius) {
                Log.d(TAG, "  ${point.name}: далеко")
                return@forEach
            }

            point.directionFilter?.let { filter ->
                val userBearing = userLocation.bearing
                if (!GeoUtils.isBearingWithinTolerance(userBearing, filter.bearing, filter.tolerance)) {
                    Log.d(TAG, "  ${point.name}: неверное направление")
                    return@forEach
                }
            }

            point.speedThreshold?.let { maxSpeed ->
                val userSpeed = userLocation.speed
                if (userSpeed > maxSpeed) {
                    Log.d(TAG, "  ${point.name}: слишком быстро")
                    return@forEach
                }
            }

            val lastTime = lastRequestTime[point.id] ?: 0L
            val intervalMs = point.httpConfig.intervalSeconds * 1000
            if (currentTime - lastTime < intervalMs) {
                return@forEach
            }

            Log.d(TAG, "  ${point.name}: отправка запроса!")
            lastRequestTime[point.id] = currentTime
            pointSendInfo[point.id] = PointSendInfo(SendStatus.SENDING)
            sendHttpRequest(point)
        }

        val newInterval = when {
            minDistance < MID_DISTANCE -> NEAR_INTERVAL_MS
            minDistance < FAR_DISTANCE -> MID_INTERVAL_MS
            else -> FAR_INTERVAL_MS
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
            val result = httpClient.sendRequest(point.httpConfig)
            result.onSuccess {
                pointSendInfo[point.id] = PointSendInfo(SendStatus.SENT)
            }.onFailure { error ->
                pointSendInfo[point.id] = PointSendInfo(
                    SendStatus.ERROR,
                    errorMessage = error.message?.take(30)
                )
            }
        }
    }

    // ── Уведомления ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Geofence Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activeCount = targetPoints.count { it.isEnabled }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Geographic HTTP Sender")
            .setContentText("Активно $activeCount из ${targetPoints.size} точек")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotificationNow() {
        val now = System.currentTimeMillis()
        val activePoints = targetPoints.filter { it.isEnabled }

        val statusText = activePoints.joinToString("\n") { point ->
            val dist = lastKnownDistance[point.id]
            val distStr = if (dist != null) "${dist.toInt()}м" else "—"

            val info = pointSendInfo[point.id]
            val statusStr = when {
                info == null -> null
                info.status == SendStatus.SENDING -> "отправляется"
                info.status == SendStatus.SENT && (now - info.timestamp) < STATUS_FRESHNESS_MS -> "отправлено"
                info.status == SendStatus.ERROR && (now - info.timestamp) < STATUS_FRESHNESS_MS -> "не пришло"
                else -> null
            }

            val name = point.name.ifBlank { point.id.take(8) }
            if (statusStr != null) "$name: $distStr, $statusStr" else "$name: $distStr"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Geographic HTTP Sender")
            .setContentText("Активно ${activePoints.size} из ${targetPoints.size} точек")
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ── Утилиты ──

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseTargetPoints(pointsJson: String): List<TargetPoint> {
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<List<TargetPoint>>() {}.type
        return gson.fromJson(pointsJson, type)
    }

    private fun resetState() {
        val validIds = targetPoints.map { it.id }.toSet()
        lastRequestTime.keys.retainAll(validIds)
        pointSendInfo.keys.retainAll(validIds)
        lastKnownDistance.keys.retainAll(validIds)
    }
}