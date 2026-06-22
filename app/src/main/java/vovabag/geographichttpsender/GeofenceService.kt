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
import vovabag.geographichttpsender.model.GlobalSettings
import vovabag.geographichttpsender.model.TargetPoint
import vovabag.geographichttpsender.network.HttpClient
import vovabag.geographichttpsender.util.GeoUtils
import kotlin.math.min

private const val TAG = "GeofenceService"

class GeofenceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val repository by lazy { SettingsRepository(this) }

    private var httpClient: HttpClient = HttpClient()
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

    // Время последнего location-апдейта (для расчёта "до следующего")
    @Volatile
    private var lastLocationUpdateTimeMs: Long = 0L

    private var currentUpdateIntervalMs: Long = 0L
    private var notificationUpdateJob: Job? = null
    private var settings = GlobalSettings.DEFAULT

    private var settingsObservationJob: Job? = null

    companion object {
        const val CHANNEL_ID = "geofence_service_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_TARGET_POINTS = "extra_target_points"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_POINTS = "ACTION_UPDATE_POINTS"
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
                serviceScope.launch {
                    settings = repository.getGlobalSettingsOnce()
                    currentUpdateIntervalMs = settings.farIntervalSec * 1000L
                    httpClient = HttpClient(
                        connectTimeoutSec = settings.connectTimeoutSec,
                        readTimeoutSec = settings.readTimeoutSec,
                        writeTimeoutSec = settings.writeTimeoutSec
                    )
                }
                val pointsJson = intent.getStringExtra(EXTRA_TARGET_POINTS)
                if (!pointsJson.isNullOrEmpty()) {
                    targetPoints = parseTargetPoints(pointsJson)
                    resetState()
                }
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                serviceScope.launch { repository.setServiceRunning(true) }
                startLocationUpdates()
                startNotificationUpdater()
                startSettingsObservation()
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                stopNotificationUpdater()
                stopSettingsObservation()
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
        stopSettingsObservation()
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

    // ── Наблюдение за настройками ──

    private fun startSettingsObservation() {
        stopSettingsObservation()
        settingsObservationJob = serviceScope.launch {
            repository.globalSettings.collect { newSettings ->
                if (newSettings != settings) {
                    settings = newSettings
                    httpClient = HttpClient(
                        connectTimeoutSec = settings.connectTimeoutSec,
                        readTimeoutSec = settings.readTimeoutSec,
                        writeTimeoutSec = settings.writeTimeoutSec
                    )
                    // Переключить интервал уведомлений — перезапустить updater
                    startNotificationUpdater()
                    Log.d(TAG, "Настройки обновлены из DataStore")
                }
            }
        }
    }

    private fun stopSettingsObservation() {
        settingsObservationJob?.cancel()
        settingsObservationJob = null
    }

    // ── Периодическое обновление уведомления ──

    private fun startNotificationUpdater() {
        stopNotificationUpdater()
        notificationUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(settings.notificationUpdateSec * 1000L)
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
        val interval = if (currentUpdateIntervalMs > 0) currentUpdateIntervalMs else settings.farIntervalSec * 1000L
        requestLocationUpdatesWithInterval(interval)
    }

    private fun requestLocationUpdatesWithInterval(intervalMs: Long) {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }

        val nearIntervalMs = settings.nearIntervalSec * 1000L
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMs
        ).apply {
            setMinUpdateIntervalMillis(intervalMs / 2)
            if (intervalMs <= nearIntervalMs) {
                setMaxUpdateDelayMillis(intervalMs * 2)
            } else {
                setMaxUpdateDelayMillis(intervalMs)
            }
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lastLocationUpdateTimeMs = System.currentTimeMillis()
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
            switchIntervalIfNeeded(settings.farIntervalSec * 1000L)
            return
        }

        val farDist = settings.farDistanceM.toFloat()
        val midDist = settings.midDistanceM.toFloat()

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

        val nearIntervalMs = settings.nearIntervalSec * 1000L
        val midIntervalMs = settings.midIntervalSec * 1000L
        val farIntervalMs = settings.farIntervalSec * 1000L

        val newInterval = when {
            minDistance < midDist -> nearIntervalMs
            minDistance < farDist -> midIntervalMs
            else -> farIntervalMs
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
        val statusFreshnessMs = settings.statusFreshnessSec * 1000L

        val statusLines = activePoints.map { point ->
            val dist = lastKnownDistance[point.id]
            val distStr = if (dist != null) "${dist.toInt()}м" else "—"

            val info = pointSendInfo[point.id]
            val statusStr = when {
                info == null -> null
                info.status == SendStatus.SENDING -> "отправляется"
                info.status == SendStatus.SENT && (now - info.timestamp) < statusFreshnessMs -> "отправлено"
                info.status == SendStatus.ERROR && (now - info.timestamp) < statusFreshnessMs -> "не пришло"
                else -> null
            }

            val name = point.name.ifBlank { point.id.take(8) }
            if (statusStr != null) "$name: $distStr, $statusStr" else "$name: $distStr"
        }

        // Последняя строка — время до следующего обновления расстояний
        val timeToNextUpdateSec = if (lastLocationUpdateTimeMs > 0 && currentUpdateIntervalMs > 0) {
            val elapsed = now - lastLocationUpdateTimeMs
            val remaining = currentUpdateIntervalMs - elapsed
            if (remaining > 0) (remaining / 1000f).coerceAtLeast(0f) else 0f
        } else null

        val lastLine = if (timeToNextUpdateSec != null) {
            "Обновление через ${"%.0f".format(timeToNextUpdateSec)}с"
        } else {
            null
        }

        val fullText = if (lastLine != null) {
            if (statusLines.isEmpty()) lastLine
            else (statusLines + lastLine).joinToString("\n")
        } else {
            statusLines.joinToString("\n")
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Geographic HTTP Sender")
            .setContentText("Активно ${activePoints.size} из ${targetPoints.size} точек")
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
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