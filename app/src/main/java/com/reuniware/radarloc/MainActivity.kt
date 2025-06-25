package com.reuniware.radarloc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
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
import java.io.Serializable
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
    private var cancellationTokenSource = CancellationTokenSource()
    private lateinit var locationCallback: LocationCallback
    private var requestingLocationUpdates = false

    private var radarDataListStateHolder by mutableStateOf<List<RadarInfo>>(emptyList())
    private var isLoadingStateHolder by mutableStateOf(true)
    private var searchQueryStateHolder by mutableStateOf("")
    private var currentSortOrderStateHolder by mutableStateOf(SortOrder.NONE)
    private var isSortAscendingStateHolder by mutableStateOf(true)
    private var userLocationStateHolder by mutableStateOf<Location?>(null)
    private var nearestRadarIdStateHolder by mutableStateOf<String?>(null)
    private var isTrackingServiceRunningStateHolder by mutableStateOf(false)
    private var hasBackgroundLocationPermStateHolder by mutableStateOf(false)
    private var lastKnownLocationTextStateHolder by mutableStateOf("Dernière localisation: Recherche...")

    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        createLocationCallback()
        initializePermissionLaunchers()
        checkInitialPermissions()

        if (hasLocationPermission()) {
            fetchLastKnownLocation()
        }

        setContent {
            RadarLocTheme {
                var radarDataList by remember { mutableStateOf(radarDataListStateHolder) }
                var isLoading by remember { mutableStateOf(isLoadingStateHolder) }
                var searchQuery by remember { mutableStateOf(searchQueryStateHolder) }
                var currentSortOrder by remember { mutableStateOf(currentSortOrderStateHolder) }
                var isSortAscending by remember { mutableStateOf(isSortAscendingStateHolder) }
                var nearestRadarId by remember { mutableStateOf(nearestRadarIdStateHolder) }
                val currentIsTrackingServiceRunning by rememberUpdatedState(isTrackingServiceRunningStateHolder)
                var lastKnownLocationText by remember { mutableStateOf(lastKnownLocationTextStateHolder) }

                LaunchedEffect(lastKnownLocationTextStateHolder) {
                    lastKnownLocationText = lastKnownLocationTextStateHolder
                }

                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    isLoadingStateHolder = true
                    isLoading = true

                    if (hasLocationPermission() && userLocationStateHolder == null) {
                        try {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                                if (location != null) {
                                    updateLocationInfo(location)
                                } else {
                                    lastKnownLocationTextStateHolder = "Dernière localisation: Non disponible"
                                }
                            }.addOnFailureListener {
                                lastKnownLocationTextStateHolder = "Dernière localisation: Erreur"
                                Log.e("MainActivity", "Erreur fetchLastKnownLocation (LaunchedEffect): ${it.message}")
                            }
                        } catch (e: SecurityException) {
                            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                            Log.w("MainActivity", "SecurityException fetchLastKnownLocation (LaunchedEffect): ${e.message}")
                        }
                    } else if (!hasLocationPermission()) {
                        lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                    }

                    val fileUrl = "https://www.data.gouv.fr/fr/datasets/r/402aa4fe-86a9-4dcd-af88-23753e290a58"
                    val fileName = "radars.csv"
                    val downloadedFile = downloadFile(applicationContext, fileUrl, fileName)

                    if (downloadedFile != null) {
                        val parsedData = parseCsv(downloadedFile)
                        radarDataListStateHolder = parsedData
                        radarDataList = parsedData

                        if (!isTrackingServiceRunningStateHolder) {
                            scope.launch {
                                requestPermissionsAndStartTracking(context, radarDataListStateHolder)
                            }
                        }

                        userLocationStateHolder?.let { loc ->
                            val nearest = findNearestRadar(loc, parsedData)
                            nearestRadarIdStateHolder = nearest?.numeroRadar
                            nearestRadarId = nearest?.numeroRadar
                        }
                    } else {
                        Log.e("RadarLoc", "Erreur de téléchargement du fichier radar.")
                        Toast.makeText(context, "Erreur de téléchargement des données radar.", Toast.LENGTH_LONG).show()
                    }
                    isLoadingStateHolder = false
                    isLoading = false
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        Text(
                            text = lastKnownLocationText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Button(
                            onClick = {
                                if (hasLocationPermission()) {
                                    scope.launch {
                                        stopLocationUpdates() // Stop existing updates
                                        val loc = getCurrentLocation()
                                        if (loc != null) {
                                            updateLocationInfo(loc)
                                        } else {
                                            Toast.makeText(context, "Localisation non trouvée.", Toast.LENGTH_SHORT).show()
                                        }
                                        startLocationUpdates() // Restart updates
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
                            Text("Trouver Radar le Plus Proche & MàJ Loc")
                        }

                        Button(
                            onClick = {
                                if (currentIsTrackingServiceRunning) {
                                    stopLocationTrackingService(context)
                                } else {
                                    scope.launch {
                                        requestPermissionsAndStartTracking(context, radarDataListStateHolder)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentIsTrackingServiceRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (currentIsTrackingServiceRunning) "Arrêter Suivi en Arrière-plan" else "Démarrer Suivi en Arrière-plan")
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

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    updateLocationInfo(location)
                }
            }
        }
    }

    private fun updateLocationInfo(location: Location) {
        userLocationStateHolder = location
        lastKnownLocationTextStateHolder =
            "Dernière loc: Lat=${String.format("%.4f", location.latitude)}, Lon=${String.format("%.4f", location.longitude)}"

        if (radarDataListStateHolder.isNotEmpty()) {
            val nearest = findNearestRadar(location, radarDataListStateHolder)
            nearestRadarIdStateHolder = nearest?.numeroRadar
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            requestingLocationUpdates = true
        } catch (e: SecurityException) {
            Log.e("MainActivity", "SecurityException in startLocationUpdates: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        requestingLocationUpdates = false
    }

    private fun fetchLastKnownLocation() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                return
            }
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        updateLocationInfo(location)
                    } else {
                        lastKnownLocationTextStateHolder = "Dernière localisation: Non disponible"
                    }
                }
                .addOnFailureListener {
                    lastKnownLocationTextStateHolder = "Dernière localisation: Erreur"
                    Log.e("MainActivity", "Erreur fetchLastKnownLocation: ${it.message}")
                }
        } catch (e: SecurityException) {
            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
            Log.w("MainActivity", "SecurityException dans fetchLastKnownLocation: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        if (cancellationTokenSource.token.isCancellationRequested) {
            cancellationTokenSource = CancellationTokenSource()
        }
        if (hasLocationPermission()) {
            if (userLocationStateHolder == null) {
                fetchLastKnownLocation()
            }
            startLocationUpdates()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!cancellationTokenSource.token.isCancellationRequested) {
            cancellationTokenSource.cancel()
        }
        stopLocationUpdates()
    }

    private fun initializePermissionLaunchers() {
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (fineLocationGranted || coarseLocationGranted) {
                fetchLastKnownLocation()
                startLocationUpdates()
                if (radarDataListStateHolder.isNotEmpty() && !isTrackingServiceRunningStateHolder) {
                    lifecycleScope.launch {
                        requestPermissionsAndStartTracking(applicationContext, radarDataListStateHolder)
                    }
                }
            } else {
                lastKnownLocationTextStateHolder = "Dernière localisation: Permission refusée"
                Toast.makeText(this, "Permission de localisation requise pour le suivi.", Toast.LENGTH_LONG).show()
            }
        }

        backgroundLocationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasBackgroundLocationPermStateHolder = isGranted
            if (isGranted) {
                if (hasLocationPermission() && radarDataListStateHolder.isNotEmpty() && !isTrackingServiceRunningStateHolder) {
                    lifecycleScope.launch {
                        startTrackingServiceIfNeeded(applicationContext, radarDataListStateHolder)
                    }
                }
            } else {
                Toast.makeText(this, "Permission de localisation en arrière-plan recommandée.", Toast.LENGTH_LONG).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                if (hasLocationPermission() &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundLocationPermStateHolder) &&
                    radarDataListStateHolder.isNotEmpty() && !isTrackingServiceRunningStateHolder
                ) {
                    lifecycleScope.launch {
                        startTrackingServiceIfNeeded(applicationContext, radarDataListStateHolder)
                    }
                }
            } else {
                Toast.makeText(this, "Permission de notification requise pour les alertes.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasBackgroundLocationPermStateHolder = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private suspend fun requestPermissionsAndStartTracking(context: Context, currentRadarData: List<RadarInfo>) {
        if (currentRadarData.isEmpty()) {
            Toast.makeText(context, "Données radar non disponibles, suivi non démarré.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermStateHolder) {
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
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission())
        ) {
            Log.w("MainActivity", "Tentative de démarrage du service mais une permission manque encore.")
            if (isTrackingServiceRunningStateHolder) isTrackingServiceRunningStateHolder = false
            return
        }

        if (currentRadarData.isNotEmpty()) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START_TRACKING
                Toast.makeText(context, "BEFORE", Toast.LENGTH_SHORT).show()
                val serializableList = ArrayList(currentRadarData.map { it.toSerializable() })
                Toast.makeText(context, "AFTER", Toast.LENGTH_SHORT).show()
                putExtra(LocationTrackingService.EXTRA_RADAR_LIST, serializableList)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Toast.makeText(context, "BEFORE startForegroundService", Toast.LENGTH_SHORT).show()
                context.startForegroundService(intent)
            } else {
                Toast.makeText(context, "BEFORE startService", Toast.LENGTH_SHORT).show()
                context.startService(intent)
            }
            isTrackingServiceRunningStateHolder = true
            Toast.makeText(context, "Suivi en arrière-plan démarré.", Toast.LENGTH_SHORT).show()
        } else {
            if (isTrackingServiceRunningStateHolder) isTrackingServiceRunningStateHolder = false
        }
    }

    private fun stopLocationTrackingService(context: Context) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP_TRACKING
        }
        context.startService(intent)
        isTrackingServiceRunningStateHolder = false
        Toast.makeText(context, "Suivi en arrière-plan arrêté.", Toast.LENGTH_SHORT).show()
    }

    private suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Permission de localisation requise.", Toast.LENGTH_SHORT).show()
            return null
        }
        if (cancellationTokenSource.token.isCancellationRequested) {
            cancellationTokenSource = CancellationTokenSource()
        }
        return try {
            withContext(Dispatchers.IO) {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).await()
            }
        } catch (e: SecurityException) {
            Log.e("RadarLoc", "getCurrentLocation Security Error: ${e.message}")
            Toast.makeText(this, "Erreur de sécurité localisation.", Toast.LENGTH_SHORT).show()
            null
        } catch (e: java.util.concurrent.CancellationException) {
            Log.i("RadarLoc", "getCurrentLocation a été annulé.")
            null
        } catch (e: Exception) {
            Log.e("RadarLoc", "getCurrentLocation Error: ${e.message}")
            Toast.makeText(this, "Erreur de localisation.", Toast.LENGTH_SHORT).show()
            null
        }
    }
}

data class RadarInfo(
    val numeroRadar: String,
    val typeRadar: String,
    val dateMiseEnService: String,
    val latitude: Double,
    val longitude: Double,
    val vma: Int
)

data class RadarInfoSerializable(
    val numeroRadar: String,
    val latitude: Double,
    val longitude: Double
) : Serializable

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
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DownloadFile", "Server error: ${connection.responseCode} ${connection.responseMessage}")
                return@withContext null
            }
            inputStream = connection.inputStream
            val outputFile = File(context.cacheDir, fileName)
            outputStream = FileOutputStream(outputFile)
            val buffer = ByteArray(4 * 1024)
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
                        Log.w("ParseCsv", "Skipping row (NumberFormat): $line - ${e.message}")
                    } catch (e: Exception) {
                        Log.w("ParseCsv", "Skipping row (General Error): $line - ${e.message}")
                    }
                } else {
                    Log.w("ParseCsv", "Skipping malformed row: $line")
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

@Composable
fun RowScope.TableCell(
    text: String,
    weight: Float,
    title: Boolean = false,
    isLatLng: Boolean = false,
    isHighlighted: Boolean = false,
    isBold: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        fontWeight = if (title || isBold) FontWeight.Bold else FontWeight.Normal,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp), contentAlignment = Alignment.Center
        ) {
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
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableCell("Radar", column1Weight, title = true, isBold = true)
                    TableCell("Type", column2Weight, title = true, isBold = true)
                    Row(
                        modifier = Modifier
                            .weight(column3Weight)
                            .clickable { onSortChange(SortOrder.BY_DATE) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TableCell("Date MES", 1f, title = true, isBold = true)
                        if (currentSortOrder == SortOrder.BY_DATE) {
                            Icon(
                                if (isSortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = "Sort Order",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    TableCell("Lat/Lon", column4Weight, title = true, isBold = true)
                    TableCell("VMA", column5Weight, title = true, isBold = true)
                }
                HorizontalDivider()
            }

            items(filteredAndSortedData, key = { radar -> radar.numeroRadar }) { radar ->
                val isNearest = radar.numeroRadar == nearestRadarId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isNearest) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                            else Color.Transparent
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableCell(radar.numeroRadar, column1Weight, isHighlighted = isNearest, isBold = isNearest)
                    TableCell(radar.typeRadar, column2Weight, isHighlighted = isNearest, isBold = isNearest)
                    TableCell(radar.dateMiseEnService, column3Weight, isHighlighted = isNearest, isBold = isNearest)
                    TableCell(
                        String.format("%.4f\n%.4f", radar.latitude, radar.longitude),
                        column4Weight,
                        isLatLng = true,
                        isHighlighted = isNearest,
                        isBold = isNearest
                    )
                    TableCell(radar.vma.toString(), column5Weight, isHighlighted = isNearest, isBold = isNearest)
                }
                HorizontalDivider()
            }
        }
    }
}