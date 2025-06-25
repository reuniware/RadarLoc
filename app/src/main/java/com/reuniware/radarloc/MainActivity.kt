package com.reuniware.radarloc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.reuniware.radarloc.ui.theme.RadarLocTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class SortOrder {
    NONE,
    BY_RADAR_NUMBER,
    BY_DATE
}

class MainActivity : ComponentActivity() {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    // CancellationTokenSource for one-shot location requests from this Activity
    private var cancellationTokenSource = CancellationTokenSource()

    private var radarDataListStateHolder by mutableStateOf<List<RadarInfo>>(emptyList())
    private var isLoadingStateHolder by mutableStateOf(true)
    private var searchQueryStateHolder by mutableStateOf("")
    private var currentSortOrderStateHolder by mutableStateOf(SortOrder.NONE)
    private var isSortAscendingStateHolder by mutableStateOf(true)
    private var userLocationStateHolder by mutableStateOf<Location?>(null)
    private var nearestRadarIdStateHolder by mutableStateOf<String?>(null)
    private var isTrackingServiceRunningStateHolder by mutableStateOf(false)
    private var hasBackgroundLocationPermStateHolder by mutableStateOf(false)

    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {

        Toast.makeText(applicationContext, "onCreate", Toast.LENGTH_SHORT).show()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initializePermissionLaunchers()
        checkInitialPermissions()

        setContent {
            RadarLocTheme {
                var radarDataList by remember { mutableStateOf(radarDataListStateHolder) }
                var isLoading by remember { mutableStateOf(isLoadingStateHolder) }
                var searchQuery by remember { mutableStateOf(searchQueryStateHolder) }
                var currentSortOrder by remember { mutableStateOf(currentSortOrderStateHolder) }
                var isSortAscending by remember { mutableStateOf(isSortAscendingStateHolder) }
                // var userLocation by remember { mutableStateOf(userLocationStateHolder) } // Not directly used in UI for now
                var nearestRadarId by remember { mutableStateOf(nearestRadarIdStateHolder) }
                var isTrackingServiceRunning by remember { mutableStateOf(isTrackingServiceRunningStateHolder) }

                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    isLoadingStateHolder = true
                    isLoading = true

                    val fileUrl = "https://www.data.gouv.fr/fr/datasets/r/402aa4fe-86a9-4dcd-af88-23753e290a58"
                    val fileName = "radars.csv"
                    val downloadedFile = downloadFile(applicationContext, fileUrl, fileName)

                    if (downloadedFile != null) {
                        val parsedData = parseCsv(downloadedFile)
                        radarDataListStateHolder = parsedData
                        radarDataList = parsedData
                        userLocationStateHolder?.let { loc ->
                            val nearest = findNearestRadar(loc, parsedData)
                            nearestRadarIdStateHolder = nearest?.numeroRadar
                            nearestRadarId = nearest?.numeroRadar
                        }
                    } else {
                        Log.e("RadarLoc", "Error downloading radar file.")
                    }
                    isLoadingStateHolder = false
                    isLoading = false
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()) {
                        Button(
                            onClick = {
                                if (hasLocationPermission()) {
                                    scope.launch {
                                        val loc = getCurrentLocation()
                                        userLocationStateHolder = loc
                                        if (loc != null) {
                                            if (radarDataListStateHolder.isNotEmpty()) {
                                                val nearest = findNearestRadar(loc, radarDataListStateHolder)
                                                nearestRadarIdStateHolder = nearest?.numeroRadar
                                                nearestRadarId = nearest?.numeroRadar
                                            }
                                        }
                                    }
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Trouver Radar le Plus Proche")
                        }

                        Button(
                            onClick = {
                                if (isTrackingServiceRunningStateHolder) {
                                    stopLocationTrackingService(context)
                                } else {
                                    requestPermissionsAndStartTracking(context, radarDataListStateHolder)
                                }
                                isTrackingServiceRunning = isTrackingServiceRunningStateHolder
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTrackingServiceRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isTrackingServiceRunning) "Arrêter Suivi en Arrière-plan" else "Démarrer Suivi en Arrière-plan")
                        }

                        if (isLoading && radarDataList.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                                Text(text = " Chargement...", modifier = Modifier.padding(top = 60.dp))
                            }
                        } else {
                            RadarDataTableScreen(
                                radarData = radarDataList,
                                searchQuery = searchQuery,
                                onSearchQueryChange = {
                                    searchQueryStateHolder = it
                                    searchQuery = it
                                },
                                currentSortOrder = currentSortOrder,
                                isSortAscending = isSortAscending,
                                onSortChange = { newSortOrder ->
                                    if (currentSortOrderStateHolder == newSortOrder) {
                                        isSortAscendingStateHolder = !isSortAscendingStateHolder
                                    } else {
                                        currentSortOrderStateHolder = newSortOrder
                                        isSortAscendingStateHolder = true
                                    }
                                    currentSortOrder = currentSortOrderStateHolder
                                    isSortAscending = isSortAscendingStateHolder
                                },
                                nearestRadarId = nearestRadarId,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Ensure the CancellationTokenSource is fresh when the activity (re)starts
        if (cancellationTokenSource.token.isCancellationRequested) {
            cancellationTokenSource = CancellationTokenSource()
        }
    }

    private fun initializePermissionLaunchers() {
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (!fineLocationGranted && !coarseLocationGranted) {
                // Potentially inform user permission is needed
            }
        }

        backgroundLocationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                hasBackgroundLocationPermStateHolder = true
                startTrackingServiceIfNeeded(this, radarDataListStateHolder)
            } else {
                hasBackgroundLocationPermStateHolder = false
                // Potentially inform user background tracking won't work
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                startTrackingServiceIfNeeded(this, radarDataListStateHolder)
            } else {
                // Potentially inform user notifications won't be shown
            }
        }
    }

    private fun checkInitialPermissions() {
        hasBackgroundLocationPermStateHolder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required or handled differently before Q for foreground services
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required before Android 13
        }
    }

    private fun requestPermissionsAndStartTracking(context: Context, currentRadarData: List<RadarInfo>) {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermStateHolder) {
            // Consider showing a rationale before requesting background location
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startTrackingServiceIfNeeded(context, currentRadarData)
    }

    private fun startTrackingServiceIfNeeded(context: Context, currentRadarData: List<RadarInfo>) {
        if (!hasLocationPermission() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermStateHolder) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission())) {
            // A permission is still missing. This check is a safeguard.
            return
        }

        if (currentRadarData.isNotEmpty()) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START_TRACKING
                // Ensure RadarInfoSerializable is defined and toSerializable() is correct
                val serializableList = ArrayList(currentRadarData.map { it.toSerializable() })
                putExtra(LocationTrackingService.EXTRA_RADAR_LIST, serializableList)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isTrackingServiceRunningStateHolder = true
        } else {
            // Potentially inform user that radar data is not available to track.
        }
    }

    private fun stopLocationTrackingService(context: Context) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP_TRACKING
        }
        context.startService(intent)
        isTrackingServiceRunningStateHolder = false
    }

    private suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null

        // Ensure the CancellationTokenSource is fresh for this specific request
        if (cancellationTokenSource.token.isCancellationRequested) {
            cancellationTokenSource = CancellationTokenSource()
        }

        return try {
            withContext(Dispatchers.IO) { // Perform network/location call off the main thread
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).await()
            }
        } catch (e: SecurityException) {
            Log.e("RadarLoc", "getCurrentLocation Security Error: ${e.message}")
            null
        } catch (e: java.util.concurrent.CancellationException) {
            Log.i("RadarLoc", "getCurrentLocation was cancelled.")
            null
        } catch (e: Exception) {
            Log.e("RadarLoc", "getCurrentLocation Error: ${e.message}")
            null
        }
    }

    override fun onStop() {
        super.onStop()
        // Cancel the current token source if it's not already cancelled
        if (!cancellationTokenSource.token.isCancellationRequested) {
            cancellationTokenSource.cancel()
        }
    }
}

// --- Data classes and Helper Functions ---
data class RadarInfo(
    val numeroRadar: String,
    val typeRadar: String,
    val dateMiseEnService: String,
    val latitude: Double,
    val longitude: Double,
    val vma: Int
)

// Ensure RadarInfoSerializable is defined (likely in LocationTrackingService.kt or a shared file)
// and accessible here. This assumes it takes (String, Double, Double).
fun RadarInfo.toSerializable(): RadarInfoSerializable {
    return RadarInfoSerializable(this.numeroRadar, this.latitude, this.longitude)
}

suspend fun downloadFile(context: Context, fileUrl: String, fileName: String): File? {
    return withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var connection: HttpURLConnection? = null
        try {
            val url = URL(fileUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000 // 15 seconds
            connection.readTimeout = 15000  // 15 seconds
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DownloadFile", "Server error: ${connection.responseCode} ${connection.responseMessage}")
                return@withContext null
            }
            inputStream = connection.inputStream
            val outputFile = File(context.cacheDir, fileName)
            outputStream = FileOutputStream(outputFile)
            val buffer = ByteArray(4 * 1024) // 4KB buffer
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            return@withContext outputFile
        } catch (e: Exception) {
            Log.e("DownloadFile", "Error downloading file: ${e.message}", e)
            return@withContext null
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
                connection?.disconnect()
            } catch (ioe: Exception) {
                Log.e("DownloadFile", "Error closing streams: ${ioe.message}", ioe)
            }
        }
    }
}

fun parseCsv(file: File): List<RadarInfo> {
    val radarInfoList = mutableListOf<RadarInfo>()
    try {
        BufferedReader(FileReader(file)).use { reader ->
            reader.readLine() // Skip header line
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val tokens = line!!.split(';')
                if (tokens.size >= 6) {
                    try {
                        val latStr = tokens[3].trim().replace(',', '.')
                        val lonStr = tokens[4].trim().replace(',', '.')
                        radarInfoList.add(
                            RadarInfo(
                                numeroRadar = tokens[0].trim(),
                                typeRadar = tokens[1].trim(),
                                dateMiseEnService = tokens[2].trim(),
                                latitude = latStr.toDoubleOrNull() ?: 0.0,
                                longitude = lonStr.toDoubleOrNull() ?: 0.0,
                                vma = tokens[5].trim().toIntOrNull() ?: 0
                            )
                        )
                    } catch (e: NumberFormatException) {
                        // Log.w("ParseCsv", "Skipping row (NumberFormat): $line - ${e.message}")
                    } catch (e: Exception){
                        // Log.w("ParseCsv", "Skipping row (General Error): $line - ${e.message}")
                    }
                } else {
                    // Log.w("ParseCsv", "Skipping malformed row: $line")
                }
            }
        }
    } catch (e: Exception) {
        Log.e("ParseCsv", "Error reading CSV file: ${e.message}", e)
    }
    return radarInfoList
}

fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val r = 6371f // Radius of earth in kilometers
    val latDistance = Math.toRadians((lat2 - lat1))
    val lonDistance = Math.toRadians((lon2 - lon1))
    val a = sin(latDistance / 2) * sin(latDistance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lonDistance / 2) * sin(lonDistance / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (r * c).toFloat()
}

fun findNearestRadar(userLocation: Location, radarList: List<RadarInfo>): RadarInfo? {
    if (radarList.isEmpty()) return null
    var minDistance = Float.MAX_VALUE
    var nearestRadar: RadarInfo? = null
    radarList.forEach { radar ->
        // Basic check for valid coordinates; adjust if 0,0 is a possible valid location in your dataset
        if (radar.latitude != 0.0 || radar.longitude != 0.0) {
            val distance = calculateDistance(userLocation.latitude, userLocation.longitude, radar.latitude, radar.longitude)
            if (distance < minDistance) {
                minDistance = distance
                nearestRadar = radar
            }
        }
    }
    return nearestRadar
}

// --- UI Composable Functions ---
@Composable
fun RadarDataTableScreen(
    radarData: List<RadarInfo>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentSortOrder: SortOrder,
    isSortAscending: Boolean,
    onSortChange: (SortOrder) -> Unit,
    nearestRadarId: String?,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val filteredAndSortedData = remember(radarData, searchQuery, currentSortOrder, isSortAscending) {
        val filtered = if (searchQuery.isNotBlank()) {
            radarData.filter {
                it.numeroRadar.contains(searchQuery, ignoreCase = true) ||
                        it.typeRadar.contains(searchQuery, ignoreCase = true) ||
                        it.dateMiseEnService.contains(searchQuery, ignoreCase = true) ||
                        it.vma.toString().contains(searchQuery, ignoreCase = true)
            }
        } else {
            radarData
        }
        when (currentSortOrder) {
            SortOrder.BY_RADAR_NUMBER -> if (isSortAscending) filtered.sortedBy { it.numeroRadar } else filtered.sortedByDescending { it.numeroRadar }
            SortOrder.BY_DATE -> if (isSortAscending) filtered.sortedBy { it.dateMiseEnService } else filtered.sortedByDescending { it.dateMiseEnService }
            SortOrder.NONE -> filtered
        }
    }

    LaunchedEffect(nearestRadarId, filteredAndSortedData) {
        if (nearestRadarId != null) {
            val indexToScrollTo = filteredAndSortedData.indexOfFirst { it.numeroRadar == nearestRadarId }
            if (indexToScrollTo != -1) {
                coroutineScope.launch {
                    lazyListState.animateScrollToItem(index = indexToScrollTo)
                }
            }
        }
    }

    if (radarData.isEmpty() && searchQuery.isBlank() && filteredAndSortedData.isEmpty()) {
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), contentAlignment = Alignment.Center) {
            Text("Aucune donnée radar disponible.", textAlign = TextAlign.Center)
        }
        return
    }

    val column1Weight = .2f; val column2Weight = .15f; val column3Weight = .25f; val column4Weight = .2f; val column5Weight = .2f

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Rechercher...") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Icône de recherche") }
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    SortableHeaderCell("N° Radar", column1Weight, currentSortOrder == SortOrder.BY_RADAR_NUMBER, isSortAscending) { onSortChange(SortOrder.BY_RADAR_NUMBER) }
                    TableCell(text = "Type", weight = column2Weight, title = true)
                    SortableHeaderCell("Mise service", column3Weight, currentSortOrder == SortOrder.BY_DATE, isSortAscending) { onSortChange(SortOrder.BY_DATE) }
                    TableCell(text = "Lat/Lon", weight = column4Weight, title = true)
                    TableCell(text = "VMA", weight = column5Weight, title = true)
                }
                HorizontalDivider()
            }

            if (filteredAndSortedData.isEmpty()) {
                item {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "Aucun résultat pour \"$searchQuery\"." else "Aucune donnée.",
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(filteredAndSortedData, key = { radar -> radar.numeroRadar }) { radar ->
                    val isNearest = radar.numeroRadar == nearestRadarId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isNearest) MaterialTheme.colorScheme.tertiaryContainer.copy(
                                    alpha = 0.7f
                                ) else Color.Transparent
                            )
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        TableCell(radar.numeroRadar, column1Weight, isHighlighted = isNearest)
                        TableCell(radar.typeRadar, column2Weight, isHighlighted = isNearest)
                        TableCell(radar.dateMiseEnService, column3Weight, isHighlighted = isNearest)
                        TableCell(String.format("%.4f\n%.4f", radar.latitude, radar.longitude), column4Weight, isLatLng = true, isHighlighted = isNearest)
                        TableCell(radar.vma.toString(), column5Weight, isHighlighted = isNearest)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun RowScope.TableCell(text: String, weight: Float, title: Boolean = false, isLatLng: Boolean = false, isHighlighted: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        fontWeight = if (title) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (isLatLng) 11.sp else 13.sp,
        color = when {
            title -> MaterialTheme.colorScheme.onPrimaryContainer
            isHighlighted -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        lineHeight = if (isLatLng) 14.sp else TextUnit.Unspecified
    )
}

@Composable
fun RowScope.SortableHeaderCell(text: String, weight: Float, isCurrentSort: Boolean, isAscending: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .weight(weight)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 4.dp)
        )
        if (isCurrentSort) {
            Icon(
                imageVector = if (isAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (isAscending) "Tri Ascendant" else "Tri Descendant",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    RadarLocTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("RadarLoc App Preview")
        }
    }
}