package com.reuniware.radarloc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.Looper // Ensure this import is present if you use Looper directly in FusedLocationProviderClient callbacks
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.Serializable

//// Make sure these data classes are defined identically to how they are in MainActivity
//// or in a shared module/file.
//data class RadarInfo(
//    val numeroRadar: String,
//    val typeRadar: String,
//    val dateMiseEnService: String,
//    val latitude: Double,
//    val longitude: Double,
//    val vma: Int
//) : Serializable {
//    fun toSerializable(): RadarInfoSerializable {
//        return RadarInfoSerializable(
//            numeroRadar = this.numeroRadar,
//            latitude = this.latitude,
//            longitude = this.longitude
//        )
//    }
//}

/*
data class RadarInfoSerializable(
    val numeroRadar: String,
    val latitude: Double,
    val longitude: Double
) : Serializable
*/


class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var radarList: List<RadarInfoSerializable> = emptyList()
    private val notifiedRadars = mutableSetOf<String>()
    private val PROXIMITY_RADIUS_METERS = 1000f // 1 km (1km)

    companion object {
        const val ACTION_START_TRACKING = "com.reuniware.radarloc.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.reuniware.radarloc.action.STOP_TRACKING"
        const val EXTRA_RADAR_FILE_PATH = "com.reuniware.radarloc.extra.RADAR_FILE_PATH" // For passing file path

        private const val NOTIFICATION_CHANNEL_ID = "LocationTrackingChannel"
        private const val FOREGROUND_NOTIFICATION_ID = 123 // Renamed for clarity
        private const val PROXIMITY_ALERT_NOTIFICATION_ID = 124
        private const val TAG = "LocationTrackingSvc"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "New location: ${location.latitude}, ${location.longitude}. Radars loaded: ${radarList.size}")
                    if (radarList.isNotEmpty()) {
                        checkProximityToRadars(location)
                    } else {
                        Log.w(TAG, "onLocationResult: Radar list is empty, cannot check proximity.")
                    }
                }
            }
        }
        Log.i(TAG, "Service onCreate called.")
        // Toast.makeText(applicationContext, "LTS:onCreate", Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand called with action: ${intent?.action}")
        // Toast.makeText(applicationContext, "LTS:onStartCommand - Action: ${intent?.action}", Toast.LENGTH_SHORT).show()

        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val radarFilePath = intent.getStringExtra(EXTRA_RADAR_FILE_PATH)
                if (radarFilePath == null) {
                    Log.e(TAG, "Radar file path is null. Stopping service.")
                    Toast.makeText(this, "Erreur: Chemin du fichier radar manquant.", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                val radarFile = File(radarFilePath)
                if (!radarFile.exists() || !radarFile.canRead()) {
                    Log.e(TAG, "Radar file does not exist or cannot be read: $radarFilePath")
                    Toast.makeText(this, "Erreur: Fichier radar inaccessible.", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Load radar data in a coroutine to avoid blocking the main thread
                serviceScope.launch(Dispatchers.IO) {
                    val parsedData = parseCsvInService(radarFile) // This returns List<RadarInfo>
                    if (parsedData.isEmpty()) {
                        Log.w(TAG, "Parsed radar list from file is empty. Stopping service.")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "Aucune donnée radar chargée.", Toast.LENGTH_LONG).show()
                            stopSelf()
                        }
                    } else {
                        radarList = parsedData.map { it.toSerializable() } // Convert to List<RadarInfoSerializable>
                        Log.i(TAG, "Successfully loaded ${radarList.size} radars from file.")
                        withContext(Dispatchers.Main) {
                            // Start foreground service and location updates on the Main thread after data is loaded
                            startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification("Suivi GPS actif (${radarList.size} radars)"))
                            startLocationUpdates()
                            Toast.makeText(applicationContext, "Suivi démarré avec ${radarList.size} radars.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return START_STICKY // Service will be restarted if killed by system after returning from here
            }
            ACTION_STOP_TRACKING -> {
                Log.i(TAG, "Stopping location tracking service via action.")
                Toast.makeText(applicationContext, "Arrêt du suivi GPS.", Toast.LENGTH_SHORT).show()
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE) // Use true or STOP_FOREGROUND_REMOVE
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "onStartCommand received unknown or null action: ${intent?.action}")
                // If service is started without a specific action (e.g., due to START_STICKY restart)
                // decide if you want to try to restart tracking or stop.
                // For now, let's stop if radarList is empty, which it would be on a raw restart without an intent.
                if (radarList.isEmpty()) {
                    Log.i(TAG, "Service restarted (sticky) but no radar data. Stopping.")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return super.onStartCommand(intent, flags, startId) // Should generally be one of the START_ constants
    }

    private fun startLocationUpdates() {
        Log.d(TAG, "Attempting to start location updates.")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000L) // 15 seconds
            .setMinUpdateIntervalMillis(10000L) // 10 seconds
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted for service. Stopping.")
            Toast.makeText(this, "Permission de localisation manquante pour le service.", Toast.LENGTH_LONG).show()
            stopSelf() // Stop the service if permissions are missing
            return
        }
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.i(TAG, "Location updates started successfully.")
            // Toast.makeText(applicationContext, "LTS:requestLocationUpdates SUCCESS", Toast.LENGTH_SHORT).show()
        } catch (unlikely: SecurityException) {
            Log.e(TAG, "Lost location permission during request for updates. $unlikely")
            Toast.makeText(this, "Erreur de sécurité lors du démarrage des mises à jour GPS.", Toast.LENGTH_LONG).show()
            stopSelf() // Stop the service
        } catch (ex: Exception) {
            Log.e(TAG, "Exception during request for updates. $ex")
            Toast.makeText(this, "Erreur inconnue lors du démarrage des mises à jour GPS.", Toast.LENGTH_LONG).show()
            stopSelf() // Stop the service
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.i(TAG, "Location updates stopped.")
        } catch (ex: Exception) {
            Log.e(TAG, "Exception while stopping location updates: $ex")
        }
    }

    private fun checkProximityToRadars(currentLocation: Location) {
        if (radarList.isEmpty()) {
            Log.d(TAG, "checkProximityToRadars called but radarList is empty.")
            return
        }

        serviceScope.launch(Dispatchers.IO) { // Perform distance calculation off the main thread
            var nearestRadarForAlert: RadarInfoSerializable? = null
            var closestDistanceToRadarForAlert = Float.MAX_VALUE

            for (radar in radarList) {
                val distanceToRadarArray = FloatArray(1)
                Location.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    radar.latitude, radar.longitude,
                    distanceToRadarArray
                )
                val distanceInMeters = distanceToRadarArray[0]

                if (distanceInMeters < PROXIMITY_RADIUS_METERS) {
                    // This radar is within the general proximity radius
                    if (!notifiedRadars.contains(radar.numeroRadar)) {
                        // We haven't notified for this radar yet during this "session" of proximity
                        if (distanceInMeters < closestDistanceToRadarForAlert) {
                            closestDistanceToRadarForAlert = distanceInMeters
                            nearestRadarForAlert = radar
                        }
                    }
                } else {
                    // This radar is outside the proximity radius
                    // If we had previously notified for it and are now far, remove it so we can notify again if user re-enters
                    if (notifiedRadars.contains(radar.numeroRadar)) {
                        Log.d(TAG, "User moved away from radar ${radar.numeroRadar}. Removing from notified set.")
                        notifiedRadars.remove(radar.numeroRadar)
                    }
                }
            }

            nearestRadarForAlert?.let { radarToAlert ->
                // Check again for notifiedRadars because the set might have been modified above
                // for radars that went out of range. This ensures we only alert for the *closest new one*.
                if (!notifiedRadars.contains(radarToAlert.numeroRadar)) {
                    withContext(Dispatchers.Main) { // Switch to Main thread for UI (Notification)
                        alertUserProximity(radarToAlert, closestDistanceToRadarForAlert)
                    }
                    notifiedRadars.add(radarToAlert.numeroRadar) // Add to notified set after alerting
                    Log.i(TAG, "Alerted for radar: ${radarToAlert.numeroRadar}. Added to notified set.")
                }
            }
        }
    }

    private fun alertUserProximity(radar: RadarInfoSerializable, distanceInMeters: Float) {
        val distanceInKm = distanceInMeters / 1000f
        val alertText = "Radar ${radar.numeroRadar} à ${"%.2f".format(distanceInKm)} km!"
        Log.i(TAG, "ALERTING USER: $alertText")

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // putExtra("highlight_radar_id", radar.numeroRadar) // Optional: for MainActivity to highlight this radar
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val alertSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val alertNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Alerte Radar à Proximité")
            .setContentText(alertText)
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Notification disappears when clicked
            .setPriority(NotificationCompat.PRIORITY_HIGH) // For heads-up notification
            .setVibrate(longArrayOf(0, 500, 200, 500)) // Vibrate pattern
            .setSound(alertSoundUri)
            .setOnlyAlertOnce(false) // Allow this specific notification to re-alert if posted again for a *different* radar
            .build()

        notificationManager.notify(PROXIMITY_ALERT_NOTIFICATION_ID, alertNotification)
    }

    private fun createForegroundNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java) // Intent to open when notification is tapped
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RadarLoc Suivi Actif")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable by swiping
            .setOnlyAlertOnce(true) // The *foreground service notification itself* will only make sound/vibrate once when first shown
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Suivi de Localisation RadarLoc"
            val importance = NotificationManager.IMPORTANCE_HIGH // High importance for proximity alerts
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = "Canal pour le service de suivi de localisation et les alertes radar."
                // You can set light color, vibration pattern for the channel itself if desired
                // channel.enableLights(true)
                // channel.lightColor = Color.RED
                // channel.enableVibration(true)
                // channel.vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    // You would need a parsing function within or accessible by the service
    // This is a simplified version, ensure it matches your CSV structure and error handling needs.
    private fun parseCsvInService(file: File): List<RadarInfo> {
        val radarInfoList = mutableListOf<RadarInfo>()
        if (!file.exists()) {
            Log.e(TAG, "parseCsvInService: File does not exist: ${file.absolutePath}")
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
                                RadarInfo( // Create RadarInfo objects
                                    numeroRadar = tokens[0].trim(),
                                    typeRadar = tokens[1].trim(), // Example: ensure these indices match your CSV
                                    dateMiseEnService = tokens[2].trim(),
                                    latitude = latStr.toDoubleOrNull() ?: 0.0,
                                    longitude = lonStr.toDoubleOrNull() ?: 0.0,
                                    vma = tokens[5].trim().toIntOrNull() ?: 0 // Example
                                )
                            )
                        } catch (e: NumberFormatException) {
                            Log.w(TAG, "Skipping row due to NumberFormatException (parseCsvInService): $line - ${e.message}")
                        } catch (e: IndexOutOfBoundsException) {
                            Log.w(TAG, "Skipping row due to IndexOutOfBoundsException (parseCsvInService): $line - ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "Skipping malformed CSV row (parseCsvInService): $line")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException in parseCsvInService: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "General error in parseCsvInService: ${e.message}", e)
        }
        Log.d(TAG, "Parsed ${radarInfoList.size} radar entries in service.")
        return radarInfoList
    }


    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "Service onBind called, returning null.")
        return null // This is a "started" service, not a "bound" one in this design.
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy called.")
        stopLocationUpdates()
        serviceJob.cancel() // Cancel all coroutines started in serviceScope
        Toast.makeText(applicationContext, "Service de suivi GPS arrêté.", Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }
}