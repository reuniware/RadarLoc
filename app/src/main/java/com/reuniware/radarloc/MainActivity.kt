package com.reuniware.radarloc // Remplacez par votre nom de package réel

//import BluetoothScanningService
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
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
import java.text.DecimalFormat
import kotlin.io.path.name

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

    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    // --- AJOUT pour les permissions Bluetooth ---
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    // --- FIN AJOUT ---

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

        // --- AJOUT : Vérifier et demander les permissions Bluetooth au démarrage ---
        // Vous pouvez déplacer cet appel si vous souhaitez le déclencher
        // sur une action utilisateur spécifique plutôt qu'au démarrage de l'activité.
        checkAndRequestBluetoothPermissions()
        // --- FIN AJOUT ---

        setContent {
            RadarLocTheme {
                val currentSearchQuery by rememberUpdatedState(searchQueryStateHolder)
                val currentSortOrder by rememberUpdatedState(currentSortOrderStateHolder)
                val currentIsSortAscending by rememberUpdatedState(isSortAscendingStateHolder)
                val currentRawRadarData by rememberUpdatedState(rawRadarDataListStateHolder)

                val displayedRadarData by remember(currentRawRadarData, currentSearchQuery, currentSortOrder, currentIsSortAscending) {
                    derivedStateOf {
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
                val listState = rememberLazyListState()

                LaunchedEffect(currentNearestRadarId, displayedRadarData) {
                    currentNearestRadarId?.let { radarId ->
                        val index = displayedRadarData.indexOfFirst { it.numeroRadar == radarId }
                        if (index != -1) {
                            val firstVisibleItemIndex = listState.firstVisibleItemIndex
                            val layoutInfo = listState.layoutInfo
                            if (layoutInfo.visibleItemsInfo.isNotEmpty()){ // Check to avoid crash if list is empty
                                val lastVisibleItemIndex = firstVisibleItemIndex + layoutInfo.visibleItemsInfo.size - 1
                                if (index < firstVisibleItemIndex || index > lastVisibleItemIndex) {
                                    listState.animateScrollToItem(index = index)
                                }
                            } else if (firstVisibleItemIndex == 0 && index == 0) { // Edge case: list has 1 item and it's the target
                                listState.animateScrollToItem(index = index)
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    isLoadingStateHolder = true
                    if (hasLocationPermission() && userLocationStateHolder == null) {
                        try {
                            getCurrentLocation()?.let { updateLocationInfo(it) } ?: fetchLastKnownLocation()
                        } catch (e: SecurityException) {
                            lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                        }
                    } else if (!hasLocationPermission()) {
                        lastKnownLocationTextStateHolder = "Dernière localisation: Permission requise"
                    }

                    val fileUrl = "https://www.data.gouv.fr/fr/datasets/r/402aa4fe-86a9-4dcd-af88-23753e290a58"
                    val fileName = "radars.csv"
                    var downloadedFile = downloadFile(applicationContext, fileUrl, fileName)

                    // AJOUTÉ: Logique de repli sur fichier local
                    if (downloadedFile == null || !downloadedFile.exists()) {
                        val localFile = File(applicationContext.filesDir, fileName)
                        if (localFile.exists()) {
                            downloadedFile = localFile // Utilise le fichier local
                            // Vous pouvez ajouter un Toast ici si vous voulez informer l'utilisateur
                            withContext(Dispatchers.Main) { // Assurez-vous d'être sur le thread principal pour le Toast
                                Toast.makeText(applicationContext, "Utilisation du fichier local des radars.", Toast.LENGTH_SHORT).show()
                            }                            // Note: 'context' n'est pas directement disponible ici, vous l'obtenez via LocalContext.current plus bas.
                            // Pour un Toast ici, il faudrait le passer en argument ou utiliser applicationContext.
                            // Pour simplifier et rester au plus proche, je n'ajoute pas le Toast directement ici pour l'instant.
                            // Vous pourrez l'ajouter dans la partie `setContent` si `downloadedFile` pointe vers `localFile`.
                        } else {
                            // Ni téléchargé, ni fichier local trouvé
                            // Le Toast d'erreur sera géré par la condition suivante (downloadedFile reste null ou non existant)
                        }
                    }
                    // FIN DE L'AJOUT
                    if (downloadedFile != null && downloadedFile.exists()) {
                        val parsedData = parseCsv(downloadedFile)
                        rawRadarDataListStateHolder = parsedData
                        userLocationStateHolder?.let { loc ->
                            findNearestRadar(loc, rawRadarDataListStateHolder)?.let { nearest ->
                                nearestRadarIdStateHolder = nearest.numeroRadar
                            }
                        }
                        if (!isTrackingServiceRunningStateHolder && rawRadarDataListStateHolder.isNotEmpty()) {
                            scope.launch {
                                requestPermissionsAndStartTracking(context, downloadedFile.absolutePath)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "Fichier radar non disponible (ni téléchargé, ni local).", Toast.LENGTH_LONG).show()
                        }
                        rawRadarDataListStateHolder = emptyList()
                    }
                    isLoadingStateHolder = false
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
                                        try { getCurrentLocation()?.let { updateLocationInfo(it) } }
                                        catch (se: SecurityException) { lastKnownLocationTextStateHolder = "Localisation: Permission refusée" }
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
                                            Toast.makeText(context, "Données radar non prêtes ou fichier manquant.", Toast.LENGTH_LONG).show()
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
                                listState = listState,
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
                val radarFile = File(applicationContext.filesDir, "radars.csv")
                if (radarFile.exists() && rawRadarDataListStateHolder.isNotEmpty() && !isTrackingServiceRunningStateHolder) {
                    lifecycleScope.launch {
                        requestPermissionsAndStartTracking(this@MainActivity, radarFile.absolutePath)
                    }
                }
            } else {
                Toast.makeText(this, "Permission de notification refusée.", Toast.LENGTH_LONG).show()
            }
        }

        // --- AJOUT : Initialisation du launcher pour les permissions Bluetooth ---
        bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "Permissions Bluetooth accordées.", Toast.LENGTH_SHORT).show()
                startBluetoothScanningService() // Démarrer le service si les permissions sont accordées
            } else {
                Toast.makeText(this, "Permissions Bluetooth refusées. Le scan ne peut pas démarrer.", Toast.LENGTH_LONG).show()
                // Logguer quelles permissions ont été refusées pour le débogage
                permissions.forEach { (perm, granted) ->
                    if (!granted) Log.w("BluetoothPermissions", "Permission refusée: $perm")
                }
            }
        }
        // --- FIN AJOUT ---

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
                    ?: lifecycleScope.launch {
                        try { getCurrentLocation()?.let { updateLocationInfo(it) } }
                        catch (se: SecurityException) { lastKnownLocationTextStateHolder = "Localisation: Permission refusée" }
                    }
            }
            .addOnFailureListener {
                lastKnownLocationTextStateHolder = "Dernière localisation: Erreur"
            }
    }

    @Throws(SecurityException::class)
    private suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) {
            lastKnownLocationTextStateHolder = "Localisation: Permission requise"
            throw SecurityException("Location permission not granted.")
        }
        return try {
            val priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
            fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token).await()
        } catch (e: SecurityException) {
            lastKnownLocationTextStateHolder = "Localisation: Permission refusée"
            throw e
        } catch (e: Exception) {
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
                locationResult.lastLocation?.let {
                    updateLocationInfo(it)
                }
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
            Toast.makeText(context, "Erreur: Fichier radar manquant pour le service.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START_TRACKING
            putExtra(LocationTrackingService.EXTRA_RADAR_FILE_PATH, radarFilePath)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
            isTrackingServiceRunningStateHolder = true
            Toast.makeText(context, "Suivi en arrière-plan démarré.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Erreur au démarrage du service de suivi.", Toast.LENGTH_LONG).show()
            isTrackingServiceRunningStateHolder = false
        }
    }

    private fun stopLocationTrackingService(context: Context) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP_TRACKING
        }
        try {
            ContextCompat.startForegroundService(context, intent)
            isTrackingServiceRunningStateHolder = false
            Toast.makeText(context, "Suivi en arrière-plan arrêté.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Erreur à l'arrêt du service de suivi.", Toast.LENGTH_LONG).show()
        }
    }

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

    private suspend fun downloadFile(context: Context, urlString: String, fileName: String): File? = withContext(Dispatchers.IO) {
        val outputFile = File(context.filesDir, fileName)
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var downloadSuccessful = false // Flag pour suivre le succès

        try {
            Log.d("DownloadFile", "Attempting to download $fileName. Current file exists: ${outputFile.exists()}, size: ${outputFile.length()}")

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000 // 10 secondes
            connection.readTimeout = 15000  // 15 secondes
            connection.connect() // Peut lever une exception ici si hors ligne

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = connection.inputStream
                // Ouvrir FileOutputStream seulement si la connexion est OK et on s'apprête à écrire
                outputStream = FileOutputStream(outputFile) // Crée/écrase le fichier ici
                val data = ByteArray(4096)
                var count: Int
                while (inputStream.read(data).also { count = it } != -1) {
                    outputStream.write(data, 0, count)
                }
                outputStream.flush() // S'assurer que tout est écrit
                downloadSuccessful = true // Marquer comme succès
                Log.d("DownloadFile", "$fileName downloaded successfully. Size: ${outputFile.length()}")
                return@withContext outputFile
            } else {
                Log.e("DownloadFile", "Server returned HTTP ${connection.responseCode} for $fileName")
                // Ne pas supprimer le fichier ici, car il pourrait être celui d'un téléchargement précédent réussi.
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("DownloadFile", "Error downloading $fileName: ${e.message}")
            // Si le téléchargement n'a PAS réussi ET qu'une tentative d'écriture a pu commencer (outputStream non null),
            // alors un fichier partiel/corrompu *pourrait* exister.
            // Cependant, dans le cas d'une simple erreur de connexion avant l'écriture,
            // outputFile.delete() supprimerait un fichier potentiellement bon.
            // Décidons de ne supprimer que si on était en train d'écrire.
            // Mais pour simplifier et éviter de supprimer un fichier valide:
            // Si le but est d'utiliser un fichier local en cache, ne supprimez pas ici.
            // Le fichier outputFile (s'il existe et est valide d'une session précédente)
            // est notre fallback si le téléchargement échoue.
            //
            // Si vous voulez être sûr de supprimer un fichier *potentiellement corrompu* par cette tentative échouée:
            // if (outputStream != null && !downloadSuccessful) {
            //     outputFile.delete()
            //     Log.d("DownloadFile", "Deleted potentially corrupt $fileName due to download error after starting write.")
            // }
            // Pour la stratégie de cache actuelle, il est plus sûr de NE PAS supprimer ici
            // et de laisser la logique de fallback utiliser le fichier s'il est valide.
            return@withContext null
        } finally {
            try { outputStream?.close() } catch (e: IOException) { Log.e("DownloadFile", "Error closing outputStream: ${e.message}") }
            try { inputStream?.close() } catch (e: IOException) { Log.e("DownloadFile", "Error closing inputStream: ${e.message}") }
            connection?.disconnect()
        }
    }

    private suspend fun parseCsv(file: File): List<RadarInfo> = withContext(Dispatchers.Default) {
        val radarInfoList = mutableListOf<RadarInfo>()
        if (!file.exists()) return@withContext radarInfoList
        var lineNumber = 0
        try {
            BufferedReader(FileReader(file)).use { reader ->
                reader.readLine()
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
                            Log.w("ParseCsv", "Ligne $lineNumber: Erreur format nombre: '$line'")
                        } catch (e: IndexOutOfBoundsException) {
                            Log.w("ParseCsv", "Ligne $lineNumber: Pas assez de colonnes: '$line'")
                        }
                    } else {
                        Log.w("ParseCsv", "Ligne $lineNumber: Ligne CSV malformée: '$line'")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ParseCsv", "Erreur parsing CSV (ligne $lineNumber): ${e.message}", e)
        }
        return@withContext radarInfoList
    }

    private fun findNearestRadar(currentLocation: Location, radars: List<RadarInfo>): RadarInfo? {
        if (radars.isEmpty()) return null
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
                Float.MAX_VALUE
            }
        }
    }

    // --- AJOUT : Logique pour les permissions et le service Bluetooth ---
    private fun checkAndRequestBluetoothPermissions() {
        val requiredPermissions = getRequiredBluetoothPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            // Toutes les permissions Bluetooth nécessaires sont déjà accordées
            Log.d("BluetoothPermissions", "Toutes les permissions Bluetooth sont déjà accordées.")
            startBluetoothScanningService()
        } else {
            Log.d("BluetoothPermissions", "Demande des permissions Bluetooth: ${permissionsToRequest.joinToString()}")
            bluetoothPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API 31)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            // Ajoutez BLUETOOTH_CONNECT si votre service en a besoin pour obtenir le nom, se connecter, etc.
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)

            // Selon la documentation Android :
            // "Si votre application utilise les résultats de la recherche Bluetooth pour déduire la position physique,
            // vous devez également déclarer la permission ACCESS_FINE_LOCATION."
            // Si vous n'utilisez PAS les résultats pour la localisation, vous pouvez ajouter
            // android:usesPermissionFlags="neverForLocation" à BLUETOOTH_SCAN dans le Manifest.
            // Pour être sûr, ou si vous pourriez déduire la localisation, incluez ACCESS_FINE_LOCATION.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Si vous avez un mécanisme séparé pour demander ACCESS_FINE_LOCATION, vous n'avez peut-être pas besoin de l'ajouter ici.
                // Cependant, si ce service Bluetooth est le seul à en avoir besoin, c'est un bon endroit.
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        } else { // Android 11 (API 30) et versions antérieures
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION) // Crucial pour le scan sur ces versions
        }
        return permissions.toTypedArray()
    }

    private fun startBluetoothScanningService() {
        // Vérifier si l'adaptateur Bluetooth est activé avant de démarrer le service
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e("BluetoothService", "Cet appareil ne supporte pas Bluetooth.")
            Toast.makeText(this, "Bluetooth non supporté sur cet appareil.", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothService", "Bluetooth n'est pas activé.")
            Toast.makeText(this, "Veuillez activer Bluetooth pour le scan des appareils.", Toast.LENGTH_LONG).show()
            // Optionnel : Demander à l'utilisateur d'activer Bluetooth de manière programmatique
            // val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // val bluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            //     if (result.resultCode == Activity.RESULT_OK) {
            //         Log.d("BluetoothService", "Bluetooth activé par l'utilisateur.")
            //         actuallyStartTheService() // Une fonction interne pour vraiment démarrer après activation
            //     } else {
            //         Log.w("BluetoothService", "L'utilisateur n'a pas activé Bluetooth.")
            //         Toast.makeText(this, "Bluetooth non activé. Le scan ne peut pas démarrer.", Toast.LENGTH_SHORT).show()
            //     }
            // }
            // bluetoothEnableLauncher.launch(enableBtIntent)
            return // Ne pas démarrer le service si Bluetooth est désactivé
        }

        actuallyStartTheService()
    }

    private fun actuallyStartTheService() {
        Log.i("MainActivity", "Permissions et état Bluetooth OK. Démarrage du BluetoothScanningService.")
        val serviceIntent = Intent(this, BluetoothScanningService::class.java) // Assurez-vous que BluetoothScanningService.kt existe
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Service de scan Bluetooth démarré.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur au démarrage du BluetoothScanningService", e)
            Toast.makeText(this, "Erreur au démarrage du service de scan Bluetooth: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Optionnel: Méthode pour arrêter le service (par exemple dans onDestroy ou sur action utilisateur)
    private fun stopBluetoothScanningService() {
        Log.d("MainActivity", "Tentative d'arrêt du BluetoothScanningService.")
        val serviceIntent = Intent(this, BluetoothScanningService::class.java)
        try {
            stopService(serviceIntent)
            Toast.makeText(this, "Service de scan Bluetooth arrêté.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur à l'arrêt du BluetoothScanningService", e)
            Toast.makeText(this, "Erreur à l'arrêt du service de scan Bluetooth.", Toast.LENGTH_LONG).show()
        }
    }
    // --- FIN AJOUT ---


    override fun onDestroy() {
        super.onDestroy()
        cancellationTokenSource.cancel()
    }
}

private val coordinateFormatter = DecimalFormat("0.00000")

@Composable
fun RadarDataTableScreen(
    radarData: List<RadarInfo>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentSortOrder: SortOrder,
    isSortAscending: Boolean,
    onSortChange: (SortOrder) -> Unit,
    nearestRadarId: String?,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("Rechercher radar...", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 2.dp, end = 2.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderCell("N° Radar", SortOrder.BY_RADAR_NUMBER, currentSortOrder, isSortAscending, onSortChange, Modifier.weight(1.6f))
            HeaderCell("Date Svc", SortOrder.BY_DATE, currentSortOrder, isSortAscending, onSortChange, Modifier.weight(1.3f))
            HeaderCellText("Type", Modifier.weight(1.2f))
            HeaderCellText("Lat", Modifier.weight(1.3f))
            HeaderCellText("Lon", Modifier.weight(1.3f))
            HeaderCellText("VMA", Modifier.weight(0.7f))
        }

        if (radarData.isEmpty()) { // Condition simplifiée pour afficher le message
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isNotBlank()) "Aucun radar ne correspond à '$searchQuery'."
                    else "Aucune donnée radar à afficher ou en cours de chargement.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(state = listState) {
                items(radarData, key = { it.numeroRadar }) { radar ->
                    RadarRow(radar = radar, isNearest = radar.numeroRadar == nearestRadarId)
                    Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
        modifier = modifier
            .clickable { onSortChange(sortOrder) }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(2.dp))
        if (currentSortOrder == sortOrder) {
            Icon(
                imageVector = if (isSortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (isSortAscending) "Croissant" else "Décroissant",
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun HeaderCellText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun RadarRow(radar: RadarInfo, isNearest: Boolean) {
    val context = LocalContext.current // <--- ADD THIS LINE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isNearest) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .clickable { // Rendre la ligne cliquable
                // Geo URI pour les applications de cartographie natives
                val nativeMapUri = Uri.parse("geo:${radar.latitude},${radar.longitude}?q=${radar.latitude},${radar.longitude}(Radar ${radar.numeroRadar})&z=15")
                val nativeMapIntent = Intent(Intent.ACTION_VIEW, nativeMapUri)

                // Vérifier d'abord si une application native peut gérer l'intention
                if (nativeMapIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(nativeMapIntent)
                } else {
                    // Si aucune application native n'est trouvée, construire une URL Google Maps pour le navigateur web
                    Toast.makeText(context, "Aucune application de carte trouvée. Ouverture dans le navigateur...", Toast.LENGTH_LONG).show()
                    val webMapUrl = "https://www.google.com/maps/search/?api=1&query=${radar.latitude},${radar.longitude}"
                    val webMapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webMapUrl))

                    // Vérifier si un navigateur web peut gérer cette URL (devrait toujours être le cas)
                    if (webMapIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(webMapIntent)
                    } else {
                        // Cas très improbable où même un navigateur n'est pas disponible
                        Toast.makeText(context, "Aucun navigateur web trouvé.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .padding(vertical = 5.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val textStyle = MaterialTheme.typography.bodySmall

        Text(radar.numeroRadar, modifier = Modifier.weight(1.6f), style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(radar.dateMiseEnService, modifier = Modifier.weight(1.3f), style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(radar.typeRadar, modifier = Modifier.weight(1.2f).padding(horizontal = 1.dp), style = textStyle, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(coordinateFormatter.format(radar.latitude), modifier = Modifier.weight(1.3f).padding(horizontal = 1.dp), style = textStyle, textAlign = TextAlign.End, maxLines = 1)
        Text(coordinateFormatter.format(radar.longitude), modifier = Modifier.weight(1.3f).padding(horizontal = 1.dp), style = textStyle, textAlign = TextAlign.End, maxLines = 1)
        Text(radar.vma.toString(), modifier = Modifier.weight(0.7f), style = textStyle, textAlign = TextAlign.Center, maxLines = 1)
    }
}