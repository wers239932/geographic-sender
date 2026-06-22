package vovabag.geographichttpsender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import vovabag.geographichttpsender.model.DirectionFilter
import vovabag.geographichttpsender.model.HttpConfig
import vovabag.geographichttpsender.model.HttpMethod
import vovabag.geographichttpsender.model.PointFolder
import vovabag.geographichttpsender.model.TargetPoint
import vovabag.geographichttpsender.ui.theme.GeographicHttpSenderTheme
import vovabag.geographichttpsender.viewmodel.MainViewModel
import vovabag.geographichttpsender.viewmodel.TestResult

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Без разрешений геолокация не работает", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()

        setContent {
            GeographicHttpSenderTheme {
                val points by viewModel.targetPoints.collectAsState()
                val folders by viewModel.pointFolders.collectAsState()
                val isServiceRunning by viewModel.isServiceRunning.collectAsState()
                val testResults by viewModel.testResults.collectAsState()
                var showAddFolderDialog by remember { mutableStateOf(false) }
                var showAddPointDialog by remember { mutableStateOf(false) }
                var createPointInFolder by remember { mutableStateOf<String?>(null) }
                var editingPoint by remember { mutableStateOf<TargetPoint?>(null) }
                var fabExpanded by remember { mutableStateOf(false) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isServiceRunning) "Сервис активен" else "Сервис остановлен",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isServiceRunning) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Активно ${points.count { it.isEnabled }} из ${points.size} точек",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (isServiceRunning) {
                                            viewModel.stopService()
                                        } else {
                                            if (points.none { it.isEnabled }) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Нужна хотя бы одна активная точка",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                viewModel.startService()
                                            }
                                        }
                                    },
                                    colors = if (isServiceRunning) {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                ) {
                                    Icon(
                                        if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isServiceRunning) "Стоп" else "Пуск")
                                }
                            }
                        }
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        MainScreen(
                            folders = folders,
                            points = points,
                            testResults = testResults,
                            padding = PaddingValues(0),
                            isServiceRunning = isServiceRunning,
                            onDeletePoint = viewModel::deletePoint,
                            onTestPoint = viewModel::testPoint,
                            onEditPoint = { editingPoint = it },
                            onClearTestResult = viewModel::clearTestResult,
                            onTogglePoint = viewModel::setPointEnabled,
                            onToggleFolder = { folderName, enabled -> viewModel.setGroupEnabled(folderName, enabled) },
                            onAddPointToFolder = { folderName ->
                                createPointInFolder = folderName
                                showAddPointDialog = true
                            },
                            onDeleteFolder = { folderId -> viewModel.deleteFolder(folderId) }
                        )

                        // FAB menu at bottom-left, overlaying other elements
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Expanded options to the right of "+"
                            AnimatedVisibility(
                                visible = fabExpanded,
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 12.dp)
                                ) {
                                    // Folder option
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Папка",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        SmallFloatingActionButton(
                                            onClick = {
                                                fabExpanded = false
                                                showAddFolderDialog = true
                                            },
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ) {
                                            Icon(Icons.Default.CreateNewFolder, null)
                                        }
                                    }

                                    // Point option
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Точка",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        SmallFloatingActionButton(
                                            onClick = {
                                                fabExpanded = false
                                                createPointInFolder = null
                                                showAddPointDialog = true
                                            },
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        ) {
                                            Icon(Icons.Default.LocationOn, null)
                                        }
                                    }
                                }
                            }

                            val rotation by animateFloatAsState(
                                targetValue = if (fabExpanded) 45f else 0f,
                                label = "fab_rotation"
                            )
                            FloatingActionButton(
                                onClick = { fabExpanded = !fabExpanded },
                                shape = CircleShape
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    null,
                                    modifier = Modifier.rotate(rotation)
                                )
                            }
                        }
                    }
                }

                if (showAddFolderDialog) {
                    AddFolderDialog(
                        onDismiss = { showAddFolderDialog = false },
                        onConfirm = { folderName ->
                            viewModel.addFolder(folderName)
                            showAddFolderDialog = false
                        }
                    )
                }

                if (showAddPointDialog || editingPoint != null) {
                    AddPointDialog(
                        existingPoint = editingPoint,
                        initialFolderName = editingPoint?.groupName ?: createPointInFolder,
                        onDismiss = {
                            editingPoint = null
                            createPointInFolder = null
                            showAddPointDialog = false
                        },
                        onConfirm = { point ->
                            if (editingPoint == null) {
                                viewModel.addPoint(point)
                            } else {
                                viewModel.updatePoint(point)
                            }
                            editingPoint = null
                            createPointInFolder = null
                            showAddPointDialog = false
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(needRequest.toTypedArray())
        }
    }
}

@Composable
fun MainScreen(
    folders: List<PointFolder>,
    points: List<TargetPoint>,
    testResults: Map<String, TestResult>,
    padding: PaddingValues,
    isServiceRunning: Boolean,
    onDeletePoint: (String) -> Unit,
    onTestPoint: (TargetPoint) -> Unit,
    onEditPoint: (TargetPoint) -> Unit,
    onClearTestResult: (String) -> Unit,
    onTogglePoint: (String, Boolean) -> Unit,
    onToggleFolder: (String?, Boolean) -> Unit,
    onAddPointToFolder: (String?) -> Unit,
    onDeleteFolder: (String) -> Unit
) {
    val folderItems = remember(folders, points) { buildFolderItems(folders, points) }
    val rootPoints = remember(points) { points.filter { it.groupName.isNullOrBlank() } }
    val expandedState = remember { mutableStateMapOf<String, Boolean>() }

    if (folderItems.isEmpty() && rootPoints.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("Нет папок и точек. Нажмите + чтобы создать.")
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(folderItems, key = { it.id }) { folderItem ->
                val isExpanded = expandedState[folderItem.id] ?: false
                FolderCard(
                    folder = folderItem,
                    isExpanded = isExpanded,
                    onToggleExpanded = {
                        expandedState[folderItem.id] = !isExpanded
                    },
                    onToggleFolder = { onToggleFolder(folderItem.name, it) },
                    onAddPoint = { onAddPointToFolder(folderItem.name) },
                    onDeleteFolder = { onDeleteFolder(folderItem.id) },
                    testResults = testResults,
                    onDeletePoint = onDeletePoint,
                    onTestPoint = onTestPoint,
                    onEditPoint = onEditPoint,
                    onClearTestResult = onClearTestResult,
                    onTogglePoint = onTogglePoint
                )
            }
            items(rootPoints, key = { "root-${it.id}" }) { point ->
                PointCard(
                    point = point,
                    testResult = testResults[point.id],
                    onDelete = { onDeletePoint(point.id) },
                    onTest = { onTestPoint(point) },
                    onEdit = { onEditPoint(point) },
                    onClearTest = { onClearTestResult(point.id) },
                    onToggleEnabled = { onTogglePoint(point.id, it) }
                )
            }
        }
    }
}

@Composable
private fun FolderCard(
    folder: FolderUi,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleFolder: (Boolean) -> Unit,
    onAddPoint: () -> Unit,
    onDeleteFolder: () -> Unit,
    testResults: Map<String, TestResult>,
    onDeletePoint: (String) -> Unit,
    onTestPoint: (TargetPoint) -> Unit,
    onEditPoint: (TargetPoint) -> Unit,
    onClearTestResult: (String) -> Unit,
    onTogglePoint: (String, Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (isExpanded) "\u25BE" else "\u25B8", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(4.dp))
                Text("\uD83D\uDCC1")
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(folder.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Точек ${folder.points.size} | Активно ${folder.enabledCount}/${folder.points.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                FilledTonalButton(onClick = { onToggleFolder(!folder.allEnabled) }) {
                    Text(if (folder.allEnabled) "Выкл" else "Вкл")
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onAddPoint) {
                    Icon(Icons.Default.Add, "Добавить точку")
                }
                IconButton(onClick = onDeleteFolder) {
                    Icon(Icons.Default.Delete, "Удалить папку", tint = MaterialTheme.colorScheme.error)
                }
            }

            if (isExpanded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (folder.points.isEmpty()) {
                            Text(
                                text = "В папке пока нет точек",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            folder.points.forEach { point ->
                                PointCard(
                                    point = point,
                                    testResult = testResults[point.id],
                                    onDelete = { onDeletePoint(point.id) },
                                    onTest = { onTestPoint(point) },
                                    onEdit = { onEditPoint(point) },
                                    onClearTest = { onClearTestResult(point.id) },
                                    onToggleEnabled = { onTogglePoint(point.id, it) },
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PointCard(
    point: TargetPoint,
    testResult: TestResult?,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onClearTest: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (point.isEnabled) 1f else 0.7f),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(point.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = point.isEnabled, onCheckedChange = onToggleEnabled)
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Редактировать")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("Координаты: ${point.latitude}, ${point.longitude}", style = MaterialTheme.typography.bodySmall)
            Text("Запрос: ${point.httpConfig.method} ${point.httpConfig.url}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Радиус: ${point.triggerRadius.toInt()} м | Интервал: ${point.httpConfig.intervalSeconds} с",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onTest,
                modifier = Modifier.fillMaxWidth(),
                enabled = point.isEnabled && testResult != TestResult.Loading
            ) {
                if (testResult == TestResult.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Отправка...")
                } else {
                    Text("Тест")
                }
            }

            testResult?.let { result ->
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = when (result) {
                        is TestResult.Success -> MaterialTheme.colorScheme.primaryContainer
                        is TestResult.Error -> MaterialTheme.colorScheme.errorContainer
                        TestResult.Loading -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (result) {
                                    is TestResult.Success -> "Успех"
                                    is TestResult.Error -> "Ошибка"
                                    TestResult.Loading -> "Загрузка..."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(onClick = onClearTest, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "Закрыть", modifier = Modifier.size(16.dp))
                            }
                        }
                        when (result) {
                            is TestResult.Error -> Text(result.message, style = MaterialTheme.typography.bodySmall)
                            is TestResult.Success -> {
                                val preview = remember(result.body) { previewMultiline(result.body) }
                                if (preview.isNotBlank()) {
                                    Text(preview, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TestResult.Loading -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая папка") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Название папки") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(folderName.trim()) },
                enabled = folderName.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun AddPointDialog(
    existingPoint: TargetPoint? = null,
    initialFolderName: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (TargetPoint) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var name by remember(existingPoint) { mutableStateOf(existingPoint?.name ?: "") }
    val fixedFolderName = existingPoint?.groupName ?: initialFolderName
    var coordinatesInput by remember(existingPoint) {
        mutableStateOf(existingPoint?.let { "${it.latitude}, ${it.longitude}" } ?: "")
    }
    var latitude by remember(existingPoint) { mutableStateOf(existingPoint?.latitude?.toString() ?: "") }
    var longitude by remember(existingPoint) { mutableStateOf(existingPoint?.longitude?.toString() ?: "") }
    var triggerRadius by remember(existingPoint) { mutableStateOf(existingPoint?.triggerRadius?.toString() ?: "100") }
    var url by remember(existingPoint) { mutableStateOf(existingPoint?.httpConfig?.url ?: "") }
    var intervalSeconds by remember(existingPoint) { mutableStateOf(existingPoint?.httpConfig?.intervalSeconds?.toString() ?: "60") }
    var useSpeedFilter by remember(existingPoint) { mutableStateOf(existingPoint?.speedThreshold != null) }
    var speedThreshold by remember(existingPoint) { mutableStateOf(existingPoint?.speedThreshold?.toString() ?: "") }
    var useDirectionFilter by remember(existingPoint) { mutableStateOf(existingPoint?.directionFilter != null) }
    var bearing by remember(existingPoint) { mutableStateOf(existingPoint?.directionFilter?.bearing?.toString() ?: "") }
    var bearingTolerance by remember(existingPoint) { mutableStateOf(existingPoint?.directionFilter?.tolerance?.toString() ?: "45") }
    var coordinatesError by remember { mutableStateOf<String?>(null) }
    var locationInProgress by remember { mutableStateOf(false) }

    fun applyCoordinates(rawValue: String) {
        coordinatesInput = rawValue
        val parsed = parseCoordinates(rawValue)
        if (parsed != null) {
            latitude = parsed.first.toString()
            longitude = parsed.second.toString()
            coordinatesError = null
        } else if (rawValue.isNotBlank()) {
            coordinatesError = "Используйте формат: 59.983798, 30.242749"
        } else {
            coordinatesError = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingPoint == null) "Новая точка" else "Редактировать точку") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (fixedFolderName != null) {
                    OutlinedTextField(
                        value = fixedFolderName,
                        onValueChange = {},
                        enabled = false,
                        label = { Text("Папка") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = coordinatesInput,
                    onValueChange = { applyCoordinates(it) },
                    label = { Text("Координаты") },
                    placeholder = { Text("59.983798, 30.242749") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { coordinatesError?.let { Text(it) } }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val clipboardText = clipboardManager.getText()?.text?.toString().orEmpty()
                            applyCoordinates(clipboardText)
                        }
                    ) {
                        Text("Вставить из буфера")
                    }
                    TextButton(
                        onClick = {
                            if (!hasLocationPermission(context)) {
                                Toast.makeText(context, "Нет доступа к геолокации", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            locationInProgress = true
                            val client = LocationServices.getFusedLocationProviderClient(context)
                            client.lastLocation
                                .addOnSuccessListener { location ->
                                    if (location != null) {
                                        applyCoordinates("${location.latitude}, ${location.longitude}")
                                        locationInProgress = false
                                    } else {
                                        client.getCurrentLocation(
                                            Priority.PRIORITY_HIGH_ACCURACY,
                                            CancellationTokenSource().token
                                        ).addOnSuccessListener { currentLocation ->
                                            currentLocation?.let {
                                                applyCoordinates("${it.latitude}, ${it.longitude}")
                                            }
                                            locationInProgress = false
                                        }.addOnFailureListener {
                                            locationInProgress = false
                                            Toast.makeText(context, "Не удалось получить координаты", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .addOnFailureListener {
                                    locationInProgress = false
                                    Toast.makeText(context, "Не удалось получить координаты", Toast.LENGTH_SHORT).show()
                                }
                        },
                        enabled = !locationInProgress
                    ) {
                        Text(if (locationInProgress) "Получение..." else "Текущие координаты")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it },
                        label = { Text("Широта") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it },
                        label = { Text("Долгота") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = triggerRadius,
                    onValueChange = { triggerRadius = it },
                    label = { Text("Радиус срабатывания (м)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = intervalSeconds,
                    onValueChange = { intervalSeconds = it },
                    label = { Text("Интервал запросов (сек)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useSpeedFilter, onCheckedChange = { useSpeedFilter = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Фильтр скорости")
                }
                if (useSpeedFilter) {
                    OutlinedTextField(
                        value = speedThreshold,
                        onValueChange = { speedThreshold = it },
                        label = { Text("Макс. скорость (м/с)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useDirectionFilter, onCheckedChange = { useDirectionFilter = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Фильтр направления")
                }
                if (useDirectionFilter) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = bearing,
                            onValueChange = { bearing = it },
                            label = { Text("Направление") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bearingTolerance,
                            onValueChange = { bearingTolerance = it },
                            label = { Text("Допуск") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        onConfirm(
                            TargetPoint(
                                id = existingPoint?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                groupName = fixedFolderName,
                                latitude = latitude.toDouble(),
                                longitude = longitude.toDouble(),
                                triggerRadius = triggerRadius.toFloatOrNull() ?: 100f,
                                httpConfig = HttpConfig(
                                    url = url.trim(),
                                    method = existingPoint?.httpConfig?.method ?: HttpMethod.GET,
                                    intervalSeconds = intervalSeconds.toLongOrNull() ?: 60
                                ),
                                directionFilter = if (useDirectionFilter && bearing.isNotBlank()) {
                                    DirectionFilter(
                                        bearing = bearing.toFloat(),
                                        tolerance = bearingTolerance.toFloatOrNull() ?: 45f
                                    )
                                } else null,
                                speedThreshold = if (useSpeedFilter && speedThreshold.isNotBlank()) {
                                    speedThreshold.toFloatOrNull()
                                } else null,
                                isEnabled = existingPoint?.isEnabled ?: true
                            )
                        )
                    } catch (_: Exception) {
                        coordinatesError = "Проверьте координаты и числовые поля"
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank() && latitude.isNotBlank() && longitude.isNotBlank()
            ) {
                Text(if (existingPoint == null) "Добавить" else "Сохранить")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )

    LaunchedEffect(existingPoint?.id) {
        if (coordinatesInput.isNotBlank()) {
            applyCoordinates(coordinatesInput)
        }
    }
}

private data class FolderUi(
    val id: String,
    val name: String?,
    val label: String,
    val points: List<TargetPoint>,
    val enabledCount: Int,
    val allEnabled: Boolean
)

private fun buildFolderItems(folders: List<PointFolder>, points: List<TargetPoint>): List<FolderUi> {
    val pointsByGroup = points.groupBy { it.groupName?.trim()?.ifEmpty { null } }
    return folders.map { folder ->
        val folderPoints = pointsByGroup[folder.name.trim()].orEmpty()
        FolderUi(
            id = folder.id,
            name = folder.name,
            label = folder.name,
            points = folderPoints,
            enabledCount = folderPoints.count { it.isEnabled },
            allEnabled = folderPoints.isNotEmpty() && folderPoints.all { it.isEnabled }
        )
    }
}

private fun parseCoordinates(value: String): Pair<Double, Double>? {
    val parts = value
        .replace(";", ",")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    return lat to lon
}

private fun previewMultiline(text: String, lines: Int = 3): String {
    return text.lineSequence().take(lines).joinToString("\n").trim()
}

private fun hasLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}