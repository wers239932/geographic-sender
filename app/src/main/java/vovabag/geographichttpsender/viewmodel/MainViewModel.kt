package vovabag.geographichttpsender.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import vovabag.geographichttpsender.GeofenceService
import vovabag.geographichttpsender.data.SettingsRepository
import vovabag.geographichttpsender.model.GlobalSettings
import vovabag.geographichttpsender.model.PointFolder
import vovabag.geographichttpsender.model.TargetPoint
import vovabag.geographichttpsender.network.HttpClient

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val httpClient = HttpClient()
    private val gson = Gson()

    private val _targetPoints = MutableStateFlow<List<TargetPoint>>(emptyList())
    val targetPoints: StateFlow<List<TargetPoint>> = _targetPoints.asStateFlow()

    private val _pointFolders = MutableStateFlow<List<PointFolder>>(emptyList())
    val pointFolders: StateFlow<List<PointFolder>> = _pointFolders.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _globalSettings = MutableStateFlow(GlobalSettings.DEFAULT)
    val globalSettings: StateFlow<GlobalSettings> = _globalSettings.asStateFlow()

    private val _testResults = MutableStateFlow<Map<String, TestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, TestResult>> = _testResults.asStateFlow()

    init {
        viewModelScope.launch {
            repository.targetPoints.collect { points ->
                _targetPoints.value = points
                if (_isServiceRunning.value) {
                    updateServicePoints()
                }
            }
        }

        viewModelScope.launch {
            repository.pointFolders.collect { folders ->
                _pointFolders.value = folders
            }
        }

        viewModelScope.launch {
            repository.serviceRunning.collect { running ->
                _isServiceRunning.value = running
            }
        }

        viewModelScope.launch {
            repository.globalSettings.collect { settings ->
                _globalSettings.value = settings
            }
        }
    }

    fun addPoint(point: TargetPoint) {
        viewModelScope.launch {
            repository.addTargetPoint(point)
        }
    }

    fun updatePoint(point: TargetPoint) {
        viewModelScope.launch {
            repository.updateTargetPoint(point)
        }
    }

    fun addFolder(name: String) {
        val normalizedName = normalizeGroupName(name) ?: return
        if (_pointFolders.value.any { normalizeGroupName(it.name) == normalizedName }) return
        viewModelScope.launch {
            repository.addPointFolder(PointFolder(name = normalizedName))
        }
    }

    fun updateFolder(folder: PointFolder) {
        val normalizedName = normalizeGroupName(folder.name) ?: return
        if (_pointFolders.value.any { it.id != folder.id && normalizeGroupName(it.name) == normalizedName }) return
        viewModelScope.launch {
            repository.updatePointFolder(folder.copy(name = normalizedName))
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            repository.deletePointFolder(folderId)
        }
    }

    fun setPointEnabled(id: String, isEnabled: Boolean) {
        val point = _targetPoints.value.firstOrNull { it.id == id } ?: return
        updatePoint(point.copy(isEnabled = isEnabled))
    }

    fun setGroupEnabled(groupName: String?, isEnabled: Boolean) {
        viewModelScope.launch {
            _targetPoints.value
                .filter { normalizeGroupName(it.groupName) == normalizeGroupName(groupName) }
                .forEach { repository.updateTargetPoint(it.copy(isEnabled = isEnabled)) }
        }
    }

    fun deletePoint(id: String) {
        viewModelScope.launch {
            repository.deleteTargetPoint(id)
        }
    }

    fun saveGlobalSettings(settings: GlobalSettings) {
        viewModelScope.launch {
            repository.saveGlobalSettings(settings)
        }
    }

    /**
     * Тестовая отправка запроса без проверки геолокации
     */
    fun testPoint(point: TargetPoint) {
        viewModelScope.launch {
            _testResults.value = _testResults.value + (point.id to TestResult.Loading)
            val result = httpClient.sendRequest(point.httpConfig)
            result.onSuccess { body ->
                _testResults.value = _testResults.value + (point.id to TestResult.Success(body))
            }.onFailure { error ->
                _testResults.value = _testResults.value + (point.id to TestResult.Error(error.message ?: "Unknown error"))
            }
        }
    }

    fun clearTestResult(id: String) {
        val current = _testResults.value.toMutableMap()
        current.remove(id)
        _testResults.value = current
    }

    fun startService() {
        val context = getApplication<Application>()
        val pointsJson = gson.toJson(_targetPoints.value)
        val intent = Intent(context, GeofenceService::class.java).apply {
            action = GeofenceService.ACTION_START
            putExtra(GeofenceService.EXTRA_TARGET_POINTS, pointsJson)
        }
        context.startForegroundService(intent)
        viewModelScope.launch {
            repository.setServiceRunning(true)
        }
    }

    fun stopService() {
        val context = getApplication<Application>()
        val intent = Intent(context, GeofenceService::class.java).apply {
            action = GeofenceService.ACTION_STOP
        }
        context.startService(intent)
        viewModelScope.launch {
            repository.setServiceRunning(false)
        }
    }

    /**
     * Обновляет точки в запущенном сервисе без перезапуска
     */
    private fun updateServicePoints() {
        val context = getApplication<Application>()
        val pointsJson = gson.toJson(_targetPoints.value)
        val intent = Intent(context, GeofenceService::class.java).apply {
            action = GeofenceService.ACTION_UPDATE_POINTS
            putExtra(GeofenceService.EXTRA_TARGET_POINTS, pointsJson)
        }
        context.startService(intent)
    }

    private fun normalizeGroupName(groupName: String?): String? {
        return groupName?.trim()?.takeIf { it.isNotEmpty() }
    }
}

sealed class TestResult {
    object Loading : TestResult()
    data class Success(val body: String) : TestResult()
    data class Error(val message: String) : TestResult()
}