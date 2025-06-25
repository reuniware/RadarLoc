package com.reuniware.radarloc // Remplacez par votre nom de package réel

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState // N'oubliez pas cet import
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState // Import nécessaire
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.reuniware.radarloc.ui.theme.RadarLocTheme // Remplacez par votre thème réel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

// Énumérations et Classes de Données
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

    // StateHolders
    private var rawRadarDataListStateHolder by mutableStateOf<List<RadarInfo>>(emptyList())
    private var isLoadingStateHolder by mutableStateOf(true)
    private var searchQueryStateHolder by mutableStateOf("")
    private var currentSortOrderStateHolder by mutableStateOf(SortOrder.NONE)
    private var isSortAscendingStateHolder by mutableStateOf(true)
    private var userLocationStateHolder by mutableStateOf<Location?>(null)
    private var nearestRadarIdStateHolder by mutableStateOf<String?>(null)
    private var isTrackingServiceRunningStateHolder by mutableStateOf(false)
    private var hasBackgroundLocationPermStateHolder by mutableStateOf(false)
    private var lastKnownLocationTextStateHolder by mutableStateOf("Dernière localisation: Recherche...")

    // Lanceurs de Permissions
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
        isTrackingServiceRunningStateHolder = isServiceRunning(this, LocationTrackingService::class.java)

        setContent {
            RadarLocTheme {
                val currentSearchQuery by rememberUpdatedState(searchQueryStateHolder)
                val currentSortOrder by rememberUpdatedState(currentSortOrderStateHolder)
                val currentIsSortAscending by rememberUpdatedState(isSortAscendingStateHolder)
                val currentRawRadarData by rememberUpdatedState(rawRadarDataListStateHolder)

                val displayedRadarData by remember(currentRawRadarData, currentSearchQuery, currentSortOrder, currentIsSortAscending) {
                    derivedStateOf {
                        Log.d("DisplayedData", "Recalculating displayedRadarData. Query: '$currentSearchQuery', Sort: $currentSortOrder, Asc: $currentIsSortAscending, RawCount: ${currentRawRadarData.size}")
                        val filteredList = if (currentSearchQuery.isBlank()) {
                            currentRawRadarData
                        } else {
                            currentRawRadarData.filter {
                                it.numeroRadar.contains(currentSearchQuery, ignoreCase = true) ||
                                        it.typeRadar.contains(currentSearchQuery, ignoreCase = true)
                            }
                        }
                        when (currentSortOrder) {
                            SortOrder.NONE -> filteredList
                            SortOrder.BY_RADAR_NUMBER -> {
                                if (currentIsSortAscending) filteredList.sortedBy { it.numeroRadar }
                                else filteredList.sortedByDescending { it.numeroRadar }
                            }
                            SortOrder.BY_DATE -> {
                                if (currentIsSortAscending) filteredList.sortedBy { it.dateMiseEnService }
                                else filteredList.sortedByDescending { it.dateMiseEnService }
                            }
                        }
                    }
                }

                val currentIsLoading by rememberUpdatedState(isLoadingStateHolder)
                val currentNearestRadarId by rememberUpdatedState(nearestRadarIdStateHolder)
                val currentIsTrackingServiceRunning by rememberUpdatedState(isTrackingServiceRunningStateHolder)
                val currentLastKnownLocationText by rememberUpdatedState(lastKnownLocationTextStateHolder)

                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val listState = rememberLazyListState() // État pour LazyColumn

                // Effet pour faire défiler jusqu'au radar le plus proche
                LaunchedEffect(currentNearestRadarId, displayedRadarData) {
                    currentNearestRadarId?.let { radarId ->
                        val index = displayedRadarData.indexOfFirst { it.numeroRadar == radarId }
                        if (index != -1) {
                            val firstVisibleItemIndex = listState.firstVisibleItemIndex
                            val lastVisibleItemIndex = firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size - 1
                            if (index < firstVisibleItemIndex || index > lastVisibleItemIndex) {
                                listState.animateScrollToItem(index = index)
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    isLoadingStateHolder = true
                    Log.d("MainActivity", "LaunchedEffect: Démarrage chargement initial.")

                    if (hasLocationPermission() && userLocationStateHolder == null) {
                        try {
                            val currentLocation = getCurrentLocation()
                            currentLocation?.let { updateLocationInfo(it) } ?: fetchLastKnownLocation()
                        } catch (e: SecurityException) {
                            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                        }
                    } else if (!hasLocationPermission()) {
                        lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                    }

                    val fileUrl = "https://www.data.gouv.fr/fr/datasets/r/402aa4fe-86a9-4dcd-af88-23753e290a58"
                    val fileName = "radars.csv"
                    var downloadedFile: File? = null
                    withContext(Dispatchers.IO) {
                        downloadedFile = downloadFile(applicationContext, fileUrl, fileName)
                    }

                    if (downloadedFile != null && downloadedFile!!.exists()) {
                        val parsedData = withContext(Dispatchers.Default) { parseCsv(downloadedFile!!) }
                        rawRadarDataListStateHolder = parsedData
                        Log.d("MainActivity", "LaunchedEffect: Données radar parsées: ${parsedData.size} éléments.")
                        userLocationStateHolder?.let { loc ->
                            findNearestRadar(loc, rawRadarDataListStateHolder)?.let { nearest ->
                                nearestRadarIdStateHolder = nearest.numeroRadar
                            }
                        }
                        if (!isTrackingServiceRunningStateHolder && rawRadarDataListStateHolder.isNotEmpty()) {
                            scope.launch {
                                requestPermissionsAndStartTracking(context, downloadedFile!!.absolutePath)
                            }
                        }
                    } else {
                        Log.e("RadarLoc", "Erreur de téléchargement du fichier radar ou fichier non trouvé.")
                        Toast.makeText(context, "Erreur de téléchargement des données radar.", Toast.LENGTH_LONG).show()
                        rawRadarDataListStateHolder = emptyList()
                    }
                    isLoadingStateHolder = false
                    Log.d("MainActivity", "LaunchedEffect: Chargement initial terminé.")
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Text(
                            text = currentLastKnownLocationText,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Button(onClick = {
                                if (hasLocationPermission()) {
                                    scope.launch {
                                        lastKnownLocationTextStateHolder = "Dernière localisation: Recherche..."
                                        getCurrentLocation()?.let { updateLocationInfo(it) }
                                    }
                                } else {
                                    locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            }) { Text("MàJ Position") }
                            Button(
                                onClick = {
                                    val radarFile = File(applicationContext.filesDir, "radars.csv")
                                    if (currentIsTrackingServiceRunning) {
                                        stopLocationTrackingService(context)
                                    } else {
                                        if (radarFile.exists() && rawRadarDataListStateHolder.isNotEmpty()) {
                                            scope.launch { requestPermissionsAndStartTracking(context, radarFile.absolutePath) }
                                        } else {
                                            Toast.makeText(context, "Données radar non prêtes.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (currentIsTrackingServiceRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary
                                )
                            ) { Text(if (currentIsTrackingServiceRunning) "Arrêter Suivi" else "Démarrer Suivi") }
                        }

                        if (currentIsLoading && rawRadarDataListStateHolder.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                                Text(" Chargement des radars...", modifier = Modifier.padding(top = 70.dp))
                            }
                        } else {
                            RadarDataTableScreen(
                                radarData = displayedRadarData,
                                searchQuery = currentSearchQuery,
                                onSearchQueryChange = { newQuery -> searchQueryStateHolder = newQuery },
                                currentSortOrder = currentSortOrder,
                                isSortAscending = currentIsSortAscending,
                                onSortChange = { newSortOrder ->
                                    if (currentSortOrderStateHolder == newSortOrder) {
                                        isSortAscendingStateHolder = !isSortAscendingStateHolder
                                    } else {
                                        currentSortOrderStateHolder = newSortOrder
                                        isSortAscendingStateHolder = true
                                    }
                                },
                                nearestRadarId = currentNearestRadarId,
                                listState = listState, // Passer listState ici
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun initializePermissionLaunchers() {
        locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fineLocationGranted || coarseLocationGranted) {
                Toast.makeText(this, "Permission de localisation accordée.", Toast.LENGTH_SHORT).show()
                fetchLastKnownLocation()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            } else {
                Toast.makeText(this, "Permission de localisation refusée.", Toast.LENGTH_LONG).show()
            }
        }

        backgroundLocationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            hasBackgroundLocationPermStateHolder = isGranted
            if (isGranted) {
                Toast.makeText(this, "Permission de localisation en arrière-plan accordée.", Toast.LENGTH_SHORT).show()
                val radarFile = File(applicationContext.filesDir, "radars.csv")
                if (radarFile.exists() && rawRadarDataListStateHolder.isNotEmpty() && !isTrackingServiceRunningStateHolder) {
                    lifecycleScope.launch {
                        requestPermissionsAndStartTracking(this@MainActivity, radarFile.absolutePath)
                    }
                }
            } else {
                Toast.makeText(this, "Permission de localisation en arrière-plan refusée.", Toast.LENGTH_LONG).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Permission de notification accordée.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission de notification refusée.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkInitialPermissions() {
        hasBackgroundLocationPermStateHolder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else { true }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else { true }
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else { true }
    }

    @Throws(SecurityException::class)
    private fun fetchLastKnownLocation() {
        if (!hasLocationPermission()) {
            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                location?.let { updateLocationInfo(it) }
                    ?: lifecycleScope.launch { getCurrentLocation()?.let { updateLocationInfo(it) } }
            }
            .addOnFailureListener {
                lastKnownLocationTextStateHolder = "Dernière localisation: Erreur"
                Log.e("MainActivity", "Erreur fetchLastKnownLocation: ${it.message}")
            }
    }

    @Throws(SecurityException::class)
    private suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) {
            lastKnownLocationTextStateHolder = "Localisation: Permission requise"
            return null
        }
        return try {
            val priority = Priority.PRIORITY_HIGH_ACCURACY
            fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token).await()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur getCurrentLocation: ${e.message}")
            lastKnownLocationTextStateHolder = "Localisation: Erreur"
            null
        }
    }

    private fun updateLocationInfo(location: Location) {
        userLocationStateHolder = location
        lastKnownLocationTextStateHolder = "Lat ${String.format("%.4f", location.latitude)}, Lon ${String.format("%.4f", location.longitude)}"
        if (rawRadarDataListStateHolder.isNotEmpty()) {
            findNearestRadar(location, rawRadarDataListStateHolder)?.let { nearest ->
                nearestRadarIdStateHolder = nearest.numeroRadar
            }
        }
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { updateLocationInfo(it) }
            }
        }
    }

    private suspend fun requestPermissionsAndStartTracking(context: Context, radarFilePath: String) {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        startLocationTrackingServiceWithFilePath(context, radarFilePath)
    }

    private fun startLocationTrackingServiceWithFilePath(context: Context, radarFilePath: String) {
        if (radarFilePath.isEmpty() || !File(radarFilePath).exists()) {
            Log.e("MainActivity", "Chemin du fichier radar vide ou fichier inexistant: $radarFilePath")
            Toast.makeText(context, "Erreur: Fichier radar manquant.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START_TRACKING
            putExtra(LocationTrackingService.EXTRA_RADAR_FILE_PATH, radarFilePath)
        }
        Log.d("MainActivity", "Tentative de démarrage du service avec le fichier: $radarFilePath")
        try {
            ContextCompat.startForegroundService(context, intent)
            isTrackingServiceRunningStateHolder = true
            Toast.makeText(context, "Suivi en arrière-plan démarré.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Échec du démarrage de LocationTrackingService", e)
            Toast.makeText(context, "Erreur au démarrage du service.", Toast.LENGTH_LONG).show()
            isTrackingServiceRunningStateHolder = false
        }
    }

    private fun stopLocationTrackingService(context: Context) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP_TRACKING
        }
        Log.d("MainActivity", "Tentative d'arrêt du service.")
        try {
            ContextCompat.startForegroundService(context, intent)
            isTrackingServiceRunningStateHolder = false
            Toast.makeText(context, "Suivi en arrière-plan arrêté.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Échec de l'envoi de la commande d'arrêt au service", e)
            Toast.makeText(context, "Erreur à l'arrêt du service.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                Log.d("isServiceRunning", "${serviceClass.simpleName} est actif.")
                return true
            }
        }
        Log.d("isServiceRunning", "${serviceClass.simpleName} n'est pas actif.")
        return false
    }

    private fun downloadFile(context: Context, urlString: String, fileName: String): File? {
        val outputFile = File(context.filesDir, fileName)
        // ... (logique de téléchargement comme précédemment)
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DownloadFile", "Serveur HTTP ${connection.responseCode} ${connection.responseMessage}")
                return null
            }
            inputStream = connection.inputStream
            outputStream = FileOutputStream(outputFile)
            val data = ByteArray(4096)
            var count: Int
            while (inputStream.read(data).also { count = it } != -1) {
                outputStream.write(data, 0, count)
            }
            Log.d("DownloadFile", "Fichier téléchargé: ${outputFile.absolutePath}")
            return outputFile
        } catch (e: Exception) {
            Log.e("DownloadFile", "Erreur de téléchargement: ${e.message}", e)
            outputFile.delete()
            return null
        } finally {
            outputStream?.close()
            inputStream?.close()
            connection?.disconnect()
        }
    }

    private fun parseCsv(file: File): List<RadarInfo> {
        val radarInfoList = mutableListOf<RadarInfo>()
        // ... (logique de parsing CSV comme précédemment)
        if (!file.exists()) {
            Log.e("ParseCsv", "Fichier non trouvé: ${file.absolutePath}")
            return radarInfoList
        }
        var lineNumber = 0
        try {
            BufferedReader(FileReader(file)).use { reader ->
                reader.readLine() // Ignorer l'en-tête
                lineNumber++
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lineNumber++
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
                            Log.w("ParseCsv", "Ligne $lineNumber: Erreur format nombre: $line - ${e.message}")
                        } catch (e: IndexOutOfBoundsException) {
                            Log.w("ParseCsv", "Ligne $lineNumber: Pas assez de colonnes: $line - ${e.message}")
                        }
                    } else {
                        Log.w("ParseCsv", "Ligne $lineNumber: Ligne CSV malformée (pas assez de tokens): $line")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ParseCsv", "Erreur de parsing CSV (ligne $lineNumber): ${e.message}", e)
        }
        Log.d("ParseCsv", "${radarInfoList.size} radars parsés.")
        return radarInfoList
    }

    private fun findNearestRadar(currentLocation: Location, radars: List<RadarInfo>): RadarInfo? {
        if (radars.isEmpty()) return null
        // ... (logique findNearestRadar comme précédemment)
        return radars.minByOrNull { radar ->
            val distanceResults = FloatArray(1)
            try {
                Location.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    radar.latitude, radar.longitude,
                    distanceResults
                )
                distanceResults[0]
            } catch (e: IllegalArgumentException) {
                Log.w("FindNearest", "Lat/Lon invalide pour radar ${radar.numeroRadar}: ${radar.latitude}, ${radar.longitude}")
                Float.MAX_VALUE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancellationTokenSource.cancel()
    }
}

// Composable pour la liste des radars (UI)
@Composable
fun RadarDataTableScreen(
    radarData: List<RadarInfo>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentSortOrder: SortOrder,
    isSortAscending: Boolean,
    onSortChange: (SortOrder) -> Unit,
    nearestRadarId: String?,
    listState: LazyListState, // Accepter LazyListState
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("Rechercher radar (numéro, type)...") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderCell("Numéro", SortOrder.BY_RADAR_NUMBER, currentSortOrder, isSortAscending, onSortChange, Modifier.weight(2f))
            HeaderCell("Date Service", SortOrder.BY_DATE, currentSortOrder, isSortAscending, onSortChange, Modifier.weight(1.5f))
            Text("Type", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("VMA", modifier = Modifier.weight(0.7f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }

        if (radarData.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isNotBlank()) "Aucun radar ne correspond à votre recherche."
                    else "Aucune donnée radar à afficher.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(state = listState) { // Appliquer listState ici
                items(radarData, key = { it.numeroRadar }) { radar ->
                    RadarRow(radar = radar, isNearest = radar.numeroRadar == nearestRadarId)
                    Divider()
                }
            }
        }
    }
}

@Composable
fun HeaderCell(
    text: String,
    sortOrder: SortOrder,
    currentSortOrder: SortOrder,
    isSortAscending: Boolean,
    onSortChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.clickable { onSortChange(sortOrder) }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(text, fontWeight = FontWeight.Bold)
        if (currentSortOrder == sortOrder) {
            Icon(
                imageVector = if (isSortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (isSortAscending) "Croissant" else "Décroissant",
                modifier = Modifier.size(18.dp).padding(start = 4.dp)
            )
        }
    }
}

@Composable
fun RadarRow(radar: RadarInfo, isNearest: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (isNearest) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(radar.numeroRadar, modifier = Modifier.weight(2f))
        Text(radar.dateMiseEnService, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        Text(radar.typeRadar, modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Text(radar.vma.toString(), modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}