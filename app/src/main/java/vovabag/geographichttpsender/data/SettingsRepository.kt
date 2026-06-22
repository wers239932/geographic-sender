package vovabag.geographichttpsender.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vovabag.geographichttpsender.model.GlobalSettings
import vovabag.geographichttpsender.model.PointFolder
import vovabag.geographichttpsender.model.TargetPoint

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.dataStore
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val TARGET_POINTS_KEY = stringPreferencesKey("target_points")
    private val POINT_FOLDERS_KEY = stringPreferencesKey("point_folders")
    private val SERVICE_RUNNING_KEY = booleanPreferencesKey("service_running")
    private val GLOBAL_SETTINGS_KEY = stringPreferencesKey("global_settings")

    private val _targetPoints = MutableStateFlow<List<TargetPoint>>(emptyList())
    val targetPoints: StateFlow<List<TargetPoint>> = _targetPoints.asStateFlow()

    private val _pointFolders = MutableStateFlow<List<PointFolder>>(emptyList())
    val pointFolders: StateFlow<List<PointFolder>> = _pointFolders.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _globalSettings = MutableStateFlow(GlobalSettings.DEFAULT)
    val globalSettings: StateFlow<GlobalSettings> = _globalSettings.asStateFlow()

    init {
        // Load initial data once, then observe only specific keys to avoid excessive recompositions
        scope.launch {
            val prefs = dataStore.data.first()
            val initialFolders = decodeFolders(prefs[POINT_FOLDERS_KEY])
            val initialPoints = sanitizePoints(decodePoints(prefs[TARGET_POINTS_KEY]), initialFolders)
            _pointFolders.value = initialFolders
            _targetPoints.value = initialPoints
            _serviceRunning.value = prefs[SERVICE_RUNNING_KEY] ?: false
            _globalSettings.value = decodeGlobalSettings(prefs[GLOBAL_SETTINGS_KEY])
        }

        // Observe only points changes (not every preference change)
        scope.launch {
            dataStore.data
                .map { prefs -> prefs[TARGET_POINTS_KEY] }
                .distinctUntilChanged()
                .collect { json ->
                    val folders = _pointFolders.value
                    val points = sanitizePoints(decodePoints(json), folders)
                    _targetPoints.value = points
                }
        }

        // Observe only folder changes
        scope.launch {
            dataStore.data
                .map { prefs -> prefs[POINT_FOLDERS_KEY] }
                .distinctUntilChanged()
                .collect { json ->
                    val folders = decodeFolders(json)
                    _pointFolders.value = folders
                    // Re-sanitize points when folders change
                    val points = sanitizePoints(decodePoints(
                        dataStore.data.first()[TARGET_POINTS_KEY]
                    ), folders)
                    _targetPoints.value = points
                }
        }

        // Observe only service running state
        scope.launch {
            dataStore.data
                .map { prefs -> prefs[SERVICE_RUNNING_KEY] ?: false }
                .distinctUntilChanged()
                .collect { running ->
                    _serviceRunning.value = running
                }
        }

        // Observe global settings changes
        scope.launch {
            dataStore.data
                .map { prefs -> prefs[GLOBAL_SETTINGS_KEY] }
                .distinctUntilChanged()
                .collect { json ->
                    _globalSettings.value = decodeGlobalSettings(json)
                }
        }
    }

    suspend fun saveTargetPoints(points: List<TargetPoint>) {
        dataStore.edit { preferences ->
            preferences[TARGET_POINTS_KEY] = gson.toJson(points)
        }
    }

    suspend fun addTargetPoint(point: TargetPoint) {
        dataStore.edit { preferences ->
            val current = decodePoints(preferences.get(TARGET_POINTS_KEY))
            preferences[TARGET_POINTS_KEY] = gson.toJson(current + point)
        }
    }

    suspend fun updateTargetPoint(point: TargetPoint) {
        dataStore.edit { preferences ->
            val current = decodePoints(preferences.get(TARGET_POINTS_KEY))
            preferences[TARGET_POINTS_KEY] = gson.toJson(
                current.map { existing ->
                    if (existing.id == point.id) point else existing
                }
            )
        }
    }

    suspend fun deleteTargetPoint(id: String) {
        dataStore.edit { preferences ->
            val current = decodePoints(preferences.get(TARGET_POINTS_KEY))
            preferences[TARGET_POINTS_KEY] = gson.toJson(current.filter { it.id != id })
        }
    }

    suspend fun addPointFolder(folder: PointFolder) {
        dataStore.edit { preferences ->
            val current = decodeFolders(preferences[POINT_FOLDERS_KEY])
            preferences[POINT_FOLDERS_KEY] = gson.toJson(current + folder)
        }
    }

    suspend fun updatePointFolder(folder: PointFolder) {
        dataStore.edit { preferences ->
            val currentFolders = decodeFolders(preferences[POINT_FOLDERS_KEY])
            val currentPoints = decodePoints(preferences[TARGET_POINTS_KEY])
            val previousName = currentFolders.firstOrNull { it.id == folder.id }?.name
            preferences[POINT_FOLDERS_KEY] = gson.toJson(
                currentFolders.map { existing ->
                    if (existing.id == folder.id) folder else existing
                }
            )
            if (previousName != null && previousName != folder.name) {
                preferences[TARGET_POINTS_KEY] = gson.toJson(
                    currentPoints.map { point ->
                        if (point.groupName == previousName) point.copy(groupName = folder.name) else point
                    }
                )
            }
        }
    }

    suspend fun deletePointFolder(folderId: String) {
        dataStore.edit { preferences ->
            val currentFolders = decodeFolders(preferences[POINT_FOLDERS_KEY])
            val folderName = currentFolders.firstOrNull { it.id == folderId }?.name
            preferences[POINT_FOLDERS_KEY] = gson.toJson(currentFolders.filter { it.id != folderId })
            if (folderName != null) {
                val currentPoints = decodePoints(preferences[TARGET_POINTS_KEY])
                preferences[TARGET_POINTS_KEY] = gson.toJson(
                    currentPoints.map { point ->
                        if (point.groupName == folderName) point.copy(groupName = null) else point
                    }
                )
            }
        }
    }

    suspend fun setServiceRunning(running: Boolean) {
        dataStore.edit { preferences ->
            preferences[SERVICE_RUNNING_KEY] = running
        }
        _serviceRunning.value = running
    }

    suspend fun saveGlobalSettings(settings: GlobalSettings) {
        dataStore.edit { preferences ->
            preferences[GLOBAL_SETTINGS_KEY] = gson.toJson(settings)
        }
        _globalSettings.value = settings
    }

    suspend fun getGlobalSettingsOnce(): GlobalSettings {
        val prefs = dataStore.data.first()
        return decodeGlobalSettings(prefs[GLOBAL_SETTINGS_KEY])
    }

    private fun decodePoints(json: String?): List<TargetPoint> {
        val type = object : TypeToken<List<TargetPoint>>() {}.type
        return runCatching { gson.fromJson<List<TargetPoint>>(json ?: "[]", type) }
            .getOrNull()
            .orEmpty()
    }

    private fun decodeFolders(json: String?): List<PointFolder> {
        val type = object : TypeToken<List<PointFolder>>() {}.type
        return runCatching { gson.fromJson<List<PointFolder>>(json ?: "[]", type) }
            .getOrNull()
            .orEmpty()
    }

    private fun decodeGlobalSettings(json: String?): GlobalSettings {
        return runCatching { gson.fromJson<GlobalSettings>(json, GlobalSettings::class.java) }
            .getOrNull()
            ?: GlobalSettings.DEFAULT
    }

    private fun sanitizePoints(points: List<TargetPoint>, folders: List<PointFolder>): List<TargetPoint> {
        val folderNames = folders.map { it.name.trim() }.toSet()
        return points.mapNotNull { point ->
            val groupName = point.groupName?.trim()?.takeIf { it.isNotEmpty() }
            when {
                groupName == null -> point.copy(groupName = null)
                groupName in folderNames -> point.copy(groupName = groupName)
                else -> null
            }
        }
    }
}