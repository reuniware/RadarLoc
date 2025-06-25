package com.reuniware.radarloc // Replace with your actual package name

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.reuniware.radarloc.ui.theme.RadarLocTheme // Replace with your actual theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.name
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Enum and Data Classes (Consider moving to separate files for better organization)
enum class SortOrder {
    NONE,
    BY_RADAR_NUMBER,
    BY_DATE
}

data class RadarInfo(
    val numeroRadar: String,
    val typeRadar: String, // Assuming these fields exist based on typical radar data
    val dateMiseEnService: String,
    val latitude: Double,
    val longitude: Double,
    val vma: Int // Assuming Vitesse Maximale Autorisée
    // Add other relevant fields from your CSV
) : Serializable {
    // This is needed if your service expects RadarInfoSerializable
    // If your service parses directly to RadarInfo, this might not be strictly needed here
    // but useful if you were to pass individual items.
    fun toSerializable(): RadarInfoSerializable {
        return RadarInfoSerializable(
            numeroRadar = this.numeroRadar,
            latitude = this.latitude,
            longitude = this.longitude
            // map other fields if RadarInfoSerializable has them
        )
    }
}

// This is what LocationTrackingService might use if it doesn't need all fields from RadarInfo
data class RadarInfoSerializable(
    val numeroRadar: String,
    val latitude: Double,
    val longitude: Double
    // Add other fields if your service needs them and they are different from RadarInfo
) : Serializable


class MainActivity : ComponentActivity() {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var cancellationTokenSource = CancellationTokenSource()
    private lateinit var locationCallback: LocationCallback
    private var requestingLocationUpdates = false // Manage this state based on service status

    // State Holders for UI
    private var radarDataListStateHolder by mutableStateOf<List<RadarInfo>>(emptyList())
    private var isLoadingStateHolder by mutableStateOf(true)
    private var searchQueryStateHolder by mutableStateOf("")
    private var currentSortOrderStateHolder by mutableStateOf(SortOrder.NONE)
    private var isSortAscendingStateHolder by mutableStateOf(true)
    private var userLocationStateHolder by mutableStateOf<Location?>(null)
    private var nearestRadarIdStateHolder by mutableStateOf<String?>(null)
    private var isTrackingServiceRunningStateHolder by mutableStateOf(false) // This should reflect the actual service state
    private var hasBackgroundLocationPermStateHolder by mutableStateOf(false) // You might manage this via checkInitialPermissions
    private var lastKnownLocationTextStateHolder by mutableStateOf("Dernière localisation: Recherche...")

    // Permission Launchers
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        createLocationCallback()
        initializePermissionLaunchers()
        checkInitialPermissions() // Call this to update initial permission states

        if (hasLocationPermission()) {
            fetchLastKnownLocation()
        }
        // TODO: Consider checking if the service is already running when the app starts
        // and update isTrackingServiceRunningStateHolder accordingly.

        setContent {
            RadarLocTheme {
                // UI state variables that recompose the UI
                var radarDataList by remember { mutableStateOf(radarDataListStateHolder) }
                var isLoading by remember { mutableStateOf(isLoadingStateHolder) }
                var searchQuery by remember { mutableStateOf(searchQueryStateHolder) }
                var currentSortOrder by remember { mutableStateOf(currentSortOrderStateHolder) }
                var isSortAscending by remember { mutableStateOf(isSortAscendingStateHolder) }
                var nearestRadarId by remember { mutableStateOf(nearestRadarIdStateHolder) }
                val currentIsTrackingServiceRunning by rememberUpdatedState(isTrackingServiceRunningStateHolder) // Use updated state for button text
                var lastKnownLocationText by remember { mutableStateOf(lastKnownLocationTextStateHolder) }


                // Update local UI state when state holders change
                LaunchedEffect(radarDataListStateHolder) { radarDataList = radarDataListStateHolder }
                LaunchedEffect(isLoadingStateHolder) { isLoading = isLoadingStateHolder }
                LaunchedEffect(searchQueryStateHolder) { searchQuery = searchQueryStateHolder }
                LaunchedEffect(currentSortOrderStateHolder) { currentSortOrder = currentSortOrderStateHolder }
                LaunchedEffect(isSortAscendingStateHolder) { isSortAscending = isSortAscendingStateHolder }
                LaunchedEffect(nearestRadarIdStateHolder) { nearestRadarId = nearestRadarIdStateHolder }
                LaunchedEffect(lastKnownLocationTextStateHolder) { lastKnownLocationText = lastKnownLocationTextStateHolder }


                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    isLoadingStateHolder = true // Start loading

                    // Initial location fetch
                    if (hasLocationPermission() && userLocationStateHolder == null) {
                        try {
                            // Request current location for more immediate feedback if lastLocation is stale
                            val currentLocation = getCurrentLocation() // Use your existing getCurrentLocation
                            if (currentLocation != null) {
                                updateLocationInfo(currentLocation)
                            } else {
                                // Fallback to lastLocation if current location fails
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
                            }
                        } catch (e: SecurityException) {
                            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                            Log.w("MainActivity", "SecurityException during location fetch (LaunchedEffect): ${e.message}")
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
                        val parsedDataForUI = withContext(Dispatchers.Default) { // Parse on Default dispatcher
                            parseCsv(downloadedFile!!)
                        }
                        radarDataListStateHolder = parsedDataForUI
                        // radarDataList will be updated by its LaunchedEffect

                        // Only attempt to start tracking if data is available and not already tracking
                        // The actual start is now inside requestPermissionsAndStartTracking
                        if (!isTrackingServiceRunningStateHolder && parsedDataForUI.isNotEmpty()) {
                            scope.launch { // Use scope for suspend function
                                requestPermissionsAndStartTracking(context, downloadedFile!!.absolutePath)
                            }
                        } else if (parsedDataForUI.isEmpty()) {
                            Log.w("MainActivity", "Parsed radar data is empty. Not starting tracking.")
                        }


                        userLocationStateHolder?.let { loc ->
                            val nearest = findNearestRadar(loc, parsedDataForUI)
                            nearestRadarIdStateHolder = nearest?.numeroRadar
                            // nearestRadarId will be updated by its LaunchedEffect
                        }
                    } else {
                        Log.e("RadarLoc", "Erreur de téléchargement du fichier radar ou fichier non trouvé.")
                        Toast.makeText(context, "Erreur de téléchargement des données radar.", Toast.LENGTH_LONG).show()
                        radarDataListStateHolder = emptyList() // Ensure list is empty on failure
                    }
                    isLoadingStateHolder = false // End loading
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
                                        isLoadingStateHolder = true
                                        lastKnownLocationTextStateHolder = "Dernière localisation: Recherche en cours..."
                                        stopLocationUpdates() // Stop existing continuous updates if any from MainActivity
                                        val loc = getCurrentLocation() // Get a fresh location
                                        if (loc != null) {
                                            updateLocationInfo(loc)
                                            // Optionally, re-calculate nearest radar if data is loaded
                                            if (radarDataListStateHolder.isNotEmpty()) {
                                                val nearest = findNearestRadar(loc, radarDataListStateHolder)
                                                nearestRadarIdStateHolder = nearest?.numeroRadar
                                            }
                                        } else {
                                            Toast.makeText(context, "Localisation non trouvée.", Toast.LENGTH_SHORT).show()
                                            lastKnownLocationTextStateHolder = "Dernière localisation: Non trouvée"
                                        }
                                        // Decide if you want to restart continuous updates here or rely on the service
                                        // For now, let's assume this button is for a one-time update in MainActivity
                                        // startLocationUpdates()
                                        isLoadingStateHolder = false
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
                                val currentRadarFile = File(applicationContext.filesDir, "radars.csv") // Or however you get the path
                                if (currentIsTrackingServiceRunning) {
                                    stopLocationTrackingService(context)
                                } else {
                                    if (currentRadarFile.exists() && radarDataListStateHolder.isNotEmpty()) {
                                        scope.launch {
                                            requestPermissionsAndStartTracking(context, currentRadarFile.absolutePath)
                                        }
                                    } else if (!currentRadarFile.exists()){
                                        Toast.makeText(context, "Données radar non téléchargées. Veuillez patienter ou relancer.", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Liste des radars vide. Impossible de démarrer le suivi.", Toast.LENGTH_LONG).show()
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

                        if (isLoading && radarDataList.isEmpty()) { // Show loading only if data is truly empty and loading
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                                Text(text = " Chargement...", modifier = Modifier.padding(top = 60.dp))
                            }
                        } else if (radarDataList.isEmpty() && !isLoading) {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("Aucune donnée radar à afficher. Vérifiez votre connexion ou réessayez.", textAlign = TextAlign.Center)
                            }
                        }
                        else {
                            // Your RadarDataTableScreen Composable
                            RadarDataTableScreen(
                                radarData = radarDataList, // This is now updated by LaunchedEffect
                                searchQuery = searchQuery,
                                onSearchQueryChange = {
                                    searchQueryStateHolder = it
                                },
                                currentSortOrder = currentSortOrder,
                                isSortAscending = isSortAscending,
                                onSortChange = { newSortOrder ->
                                    if (currentSortOrderStateHolder == newSortOrder) {
                                        isSortAscendingStateHolder = !isSortAscendingStateHolder
                                    } else {
                                        currentSortOrderStateHolder = newSortOrder
                                        isSortAscendingStateHolder = true // Default to ascending on new sort
                                    }
                                },
                                nearestRadarId = nearestRadarId, // This is now updated by LaunchedEffect
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
                fetchLastKnownLocation() // Fetch location now that permission is granted
                // Potentially trigger background permission if needed and conditions are met
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!hasBackgroundLocationPermission()) {
                        // Consider showing rationale before this
                        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
            } else {
                Toast.makeText(this, "Permission de localisation refusée.", Toast.LENGTH_LONG).show()
            }
        }

        backgroundLocationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                hasBackgroundLocationPermStateHolder = true
                Toast.makeText(this, "Permission de localisation en arrière-plan accordée.", Toast.LENGTH_SHORT).show()
                // You might want to automatically start the service if all conditions are now met
                // For example, if radar data is loaded and foreground permissions are also granted:
                // checkAndStartTrackingServiceIfNeeded()
            } else {
                hasBackgroundLocationPermStateHolder = false
                Toast.makeText(this, "Permission de localisation en arrière-plan refusée.", Toast.LENGTH_LONG).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Permission de notification accordée.", Toast.LENGTH_SHORT).show()
                // Proceed with operations requiring notifications
            } else {
                Toast.makeText(this, "Permission de notification refusée.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkInitialPermissions() {
        hasBackgroundLocationPermStateHolder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required before Q
        }
        // You can check other initial permissions here if needed
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required before Tiramisu
        }
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required before Q
        }
    }


    @Throws(SecurityException::class)
    private fun fetchLastKnownLocation() {
        if (!hasLocationPermission()) {
            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    updateLocationInfo(location)
                } else {
                    lastKnownLocationTextStateHolder = "Dernière localisation: Non disponible, tentative de localisation actuelle..."
                    // Try to get current location if last known is null
                    lifecycleScope.launch { getCurrentLocation()?.let { updateLocationInfo(it) } }
                }
            }
            .addOnFailureListener {
                lastKnownLocationTextStateHolder = "Dernière localisation: Erreur"
                Log.e("MainActivity", "Erreur fetchLastKnownLocation: ${it.message}")
            }
    }

    @Throws(SecurityException::class)
    private suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) {
            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
            // locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return null
        }
        return try {
            val priority = Priority.PRIORITY_HIGH_ACCURACY
            fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token).await()
        } catch (e: Exception) {
            // Includes SecurityException if permissions are revoked, or others
            Log.e("MainActivity", "Erreur getCurrentLocation: ${e.message}")
            lastKnownLocationTextStateHolder = "Dernière localisation: Erreur de récupération"
            null
        }
    }


    private fun updateLocationInfo(location: Location) {
        userLocationStateHolder = location
        lastKnownLocationTextStateHolder = "Dernière localisation: Lat ${String.format("%.4f", location.latitude)}, Lon ${String.format("%.4f", location.longitude)}"
        // If radar data is loaded, find nearest
        if (radarDataListStateHolder.isNotEmpty()) {
            val nearest = findNearestRadar(location, radarDataListStateHolder)
            nearestRadarIdStateHolder = nearest?.numeroRadar
        }
    }


    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateLocationInfo(location)
                    // If service is running, it handles its own location updates.
                    // This callback is more for MainActivity's own foreground location needs.
                }
            }
        }
    }

    @Throws(SecurityException::class)
    private fun startLocationUpdates() { // For MainActivity foreground updates, if needed
        if (!hasLocationPermission()) return
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L) // e.g., every 10 seconds
            .setMinUpdateIntervalMillis(5000L)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            requestingLocationUpdates = true
        } catch (unlikely: SecurityException) {
            requestingLocationUpdates = false
            Log.e("MainActivity", "Lost location permission. Could not start updates. $unlikely")
        }
    }

    private fun stopLocationUpdates() { // For MainActivity foreground updates
        if (requestingLocationUpdates) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                requestingLocationUpdates = false
            } catch (ex: Exception) {
                Log.i("MainActivity", "Failed to remove location updates: $ex")
            }
        }
    }

    // --- Service Control ---
    private suspend fun requestPermissionsAndStartTracking(context: Context, radarFilePath: String) {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Toast.makeText(context, "Permission de localisation requise.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            // Wait for user interaction, don't proceed to start service immediately
            Toast.makeText(context, "Permission de notification requise pour les alertes.", Toast.LENGTH_LONG).show()
            return
        }

        // Background permission is crucial for the service to run effectively
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            // Wait for user interaction
            Toast.makeText(context, "Permission de localisation en arrière-plan requise.", Toast.LENGTH_LONG).show()
            return
        }

        // If all permissions are granted, start the service
        startLocationTrackingServiceWithFilePath(context, radarFilePath)
    }

    private fun startLocationTrackingServiceWithFilePath(context: Context, radarFilePath: String) {
        if (isTrackingServiceRunningStateHolder) {
            // Log.d("MainActivity", "Service is already considered running.")
            // Toast.makeText(context, "Le suivi est déjà actif.", Toast.LENGTH_SHORT).show()
            // return // Or decide if you want to restart/update it
        }

        if (radarFilePath.isEmpty() || !File(radarFilePath).exists()) {
            Log.e("MainActivity", "Cannot start service, radar file path is empty or file does not exist: $radarFilePath")
            Toast.makeText(context, "Erreur: Fichier radar manquant ou chemin invalide.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START_TRACKING
            putExtra(LocationTrackingService.EXTRA_RADAR_FILE_PATH, radarFilePath)
        }

        Log.d("MainActivity", "Attempting to start service with file path: $radarFilePath")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            isTrackingServiceRunningStateHolder = true
            Toast.makeText(context, "Suivi en arrière-plan démarré.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start LocationTrackingService", e)
            Toast.makeText(context, "Erreur au démarrage du service de suivi.", Toast.LENGTH_LONG).show()
            isTrackingServiceRunningStateHolder = false // Ensure state reflects failure
        }
    }

    private fun stopLocationTrackingService(context: Context) {
        if (!isTrackingServiceRunningStateHolder && !isServiceRunning(context, LocationTrackingService::class.java)) { // Double check if actually running
            // Log.d("MainActivity", "Service is not considered running, or already stopped.")
            // return
        }
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP_TRACKING
        }
        Log.d("MainActivity", "Attempting to stop service.")
        try {
            // It's usually enough to just send the stop intent.
            // The service should handle its own stopForeground and stopSelf.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent) // Or just startService, service decides
            } else {
                context.startService(intent)
            }
            // context.stopService(intent) // This can also be used, but ACTION_STOP_TRACKING gives service more control
            isTrackingServiceRunningStateHolder = false
            Toast.makeText(context, "Suivi en arrière-plan arrêté.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to send stop command to LocationTrackingService", e)
            Toast.makeText(context, "Erreur à l'arrêt du service de suivi.", Toast.LENGTH_LONG).show()
        }
    }

    // Helper to check if a service is running (optional, but can be useful)
    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }


    // --- File Operations ---
    // Make sure this function is robust (error handling, closing streams)
    private fun downloadFile(context: Context, urlString: String, fileName: String): File? {
        // Ensure this saves to a location the service can also access if needed,
        // though with path passing, app's internal filesDir is fine.
        val outputFile = File(context.filesDir, fileName)
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DownloadFile", "Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                return null
            }

            inputStream = connection.inputStream
            outputStream = FileOutputStream(outputFile)

            val data = ByteArray(4096)
            var count: Int
            while (inputStream.read(data).also { count = it } != -1) {
                outputStream.write(data, 0, count)
            }
            Log.d("DownloadFile", "File downloaded successfully: ${outputFile.absolutePath}")
            return outputFile
        } catch (e: Exception) {
            Log.e("DownloadFile", "Error downloading file: ${e.message}", e)
            outputFile.delete() // Clean up partial file on error
            return null
        } finally {
            try {
                outputStream?.close()
                inputStream?.close()
            } catch (e: IOException) {
                Log.e("DownloadFile", "Error closing streams: ${e.message}")
            }
            connection?.disconnect()
        }
    }

    // Make sure this function is robust
    private fun parseCsv(file: File): List<RadarInfo> {
        val radarInfoList = mutableListOf<RadarInfo>()
        if (!file.exists()) {
            Log.e("ParseCsv", "File does not exist: ${file.absolutePath}")
            return radarInfoList
        }
        try {
            BufferedReader(FileReader(file)).use { reader ->
                reader.readLine() // Skip header line, adjust if your CSV has no header
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line!!.split(';') // Assuming semicolon delimiter
                    if (tokens.size >= 6) { // Adjust token count based on your CSV structure for RadarInfo
                        try {
                            val latStr = tokens[3].trim().replace(',', '.') // Assuming lat is at index 3
                            val lonStr = tokens[4].trim().replace(',', '.') // Assuming lon is at index 4
                            radarInfoList.add(
                                RadarInfo(
                                    numeroRadar = tokens[0].trim(),
                                    typeRadar = tokens[1].trim(), // Example: ensure these indices match your CSV
                                    dateMiseEnService = tokens[2].trim(),
                                    latitude = latStr.toDoubleOrNull() ?: 0.0,
                                    longitude = lonStr.toDoubleOrNull() ?: 0.0,
                                    vma = tokens[5].trim().toIntOrNull() ?: 0 // Example
                                )
                            )
                        } catch (e: NumberFormatException) {
                            Log.w("ParseCsv", "Skipping row due to NumberFormatException: $line - ${e.message}")
                        } catch (e: IndexOutOfBoundsException) {
                            Log.w("ParseCsv", "Skipping row due to IndexOutOfBoundsException (not enough columns?): $line - ${e.message}")
                        }
                    } else {
                        Log.w("ParseCsv", "Skipping malformed CSV row (not enough tokens): $line")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ParseCsv", "Error parsing CSV: ${e.message}", e)
        }
        Log.d("ParseCsv", "Parsed ${radarInfoList.size} radar entries.")
        return radarInfoList
    }


    // --- Utility Functions ---
    private fun findNearestRadar(currentLocation: Location, radars: List<RadarInfo>): RadarInfo? {
        if (radars.isEmpty()) return null
        var nearestRadar: RadarInfo? = null
        var minDistance = Float.MAX_VALUE

        for (radar in radars) {
            val distanceResults = FloatArray(1)
            try {
                Location.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    radar.latitude, radar.longitude,
                    distanceResults
                )
                if (distanceResults[0] < minDistance) {
                    minDistance = distanceResults[0]
                    nearestRadar = radar
                }
            } catch (e: IllegalArgumentException) {
                Log.w("FindNearest", "Invalid lat/lon for radar ${radar.numeroRadar}: ${radar.latitude}, ${radar.longitude}")
            }
        }
        Log.d("FindNearest", "Nearest radar: ${nearestRadar?.numeroRadar} at ${minDistance}m")
        return nearestRadar
    }

    override fun onDestroy() {
        super.onDestroy()
        cancellationTokenSource.cancel() // Cancel any ongoing fusedLocationClient.getCurrentLocation calls
        stopLocationUpdates() // Clean up MainActivity's own location updates
    }
}

// Dummy Composable for RadarDataTableScreen - Replace with your actual implementation
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
    // Implement your data table UI here
    Column(modifier = modifier.padding(16.dp)) {
        Text("Radar Data Table Placeholder", style = MaterialTheme.typography.headlineSmall)
        Text("Items: ${radarData.size}, Search: '$searchQuery', Nearest: $nearestRadarId")
        // Add LazyColumn or other UI elements to display radarData
    }
}