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
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.isEmpty
//import androidx.compose.ui.test.cancel
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
//import androidx.privacysandbox.tools.core.generator.build
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
import java.io.Serializable
import kotlin.collections.remove
import kotlin.text.format

/*
// Make sure this matches the structure expected by MainActivity
data class RadarInfoSerializable(
    val numeroRadar: String,
    val latitude: Double, // Corrected to kotlin.Double
    val longitude: Double // Corrected to kotlin.Double
) : Serializable
*/

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var radarList: List<RadarInfoSerializable> = kotlin.collections.emptyList()
    private val notifiedRadars = kotlin.collections.mutableSetOf<String>()
    private val PROXIMITY_RADIUS_METERS = 1000f // 1 km

    companion object {
        const val ACTION_START_TRACKING = "com.reuniware.radarloc.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.reuniware.radarloc.action.STOP_TRACKING"
        const val EXTRA_RADAR_LIST = "com.reuniware.radarloc.extra.RADAR_LIST"
        private const val NOTIFICATION_CHANNEL_ID = "LocationTrackingChannel"
        private const val NOTIFICATION_ID = 123
        private const val PROXIMITY_ALERT_NOTIFICATION_ID = 124
        private const val TAG = "LocationTrackingSvc"
    }

    override fun onCreate() {
        Toast.makeText(applicationContext, "LST:onCreate", Toast.LENGTH_SHORT).show()

        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                Toast.makeText(applicationContext, "LST:onLocationResult " + locationResult.lastLocation?.latitude + " " + locationResult.lastLocation?.longitude, Toast.LENGTH_SHORT)
                    .show()
                locationResult.lastLocation?.let { location ->
                    // Log.d(TAG, "New location: ${location.latitude}, ${location.longitude}")
                    checkProximityToRadars(location)
                }
            }
        }

        // These 2 lines of code should not be called here but for now it is the only working stuff.
        // Put here because onStartCommand is never called and I cannot find why.
        startForeground(NOTIFICATION_ID, createForegroundNotification("Suivi GPS actif..."))
        startLocationUpdates()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Toast.makeText(applicationContext, "LST:onStartCommand", Toast.LENGTH_SHORT).show()
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val serializableList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_RADAR_LIST, ArrayList::class.java) as? ArrayList<*>
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_RADAR_LIST) as? ArrayList<*>
                }
                radarList = (serializableList ?: kotlin.collections.emptyList()) as List<RadarInfoSerializable>

                if (radarList.isEmpty()) {
                    Log.w(TAG, "Radar list is empty. Stopping service.")
                    stopSelf()
                    return android.app.Service.START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, createForegroundNotification("Suivi GPS actif..."))
                startLocationUpdates()
                Log.i(TAG, "Location tracking service started with ${radarList.size} radars.")
                return START_STICKY
            }
            ACTION_STOP_TRACKING -> {
                Log.i(TAG, "Stopping location tracking service.")
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return android.app.Service.START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startLocationUpdates() {
        Toast.makeText(applicationContext, "LST:startLocationUpdates", Toast.LENGTH_SHORT).show()
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000L) // 15 seconds
            .setMinUpdateIntervalMillis(10000L) // 10 seconds
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted for service. Stopping.")
            // Consider sending a broadcast to the UI to inform the user or request permission
            stopSelf()
            return
        }
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Toast.makeText(applicationContext, "LST:requestLocationUpdates", Toast.LENGTH_SHORT).show()
        } catch (unlikely: java.lang.SecurityException) {
            Log.e(TAG, "Lost location permission during request. $unlikely")
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun checkProximityToRadars(currentLocation: Location) {
        if (radarList.isEmpty()) return

        serviceScope.launch(Dispatchers.IO) {
            var nearestRadarForAlert: RadarInfoSerializable? = null
            var closestDistanceToRadarForAlert = Float.MAX_VALUE

            for (radar in radarList) {
                val distanceToRadarArray = FloatArray(1)
                Location.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    radar.latitude, radar.longitude, // Correctly uses kotlin.Double
                    distanceToRadarArray
                )
                val distanceInMeters = distanceToRadarArray[0]

                if (distanceInMeters < PROXIMITY_RADIUS_METERS) {
                    if (!notifiedRadars.contains(radar.numeroRadar)) {
                        if (distanceInMeters < closestDistanceToRadarForAlert) {
                            closestDistanceToRadarForAlert = distanceInMeters
                            nearestRadarForAlert = radar
                        }
                    }
                } else {
                    if (notifiedRadars.contains(radar.numeroRadar)) {
                        notifiedRadars.remove(radar.numeroRadar)
                    }
                }
            }

            nearestRadarForAlert?.let { radarToAlert ->
                if (!notifiedRadars.contains(radarToAlert.numeroRadar)) {
                    withContext(Dispatchers.Main) {
                        alertUserProximity(radarToAlert, closestDistanceToRadarForAlert) // Correctly passes kotlin.Float
                    }
                    notifiedRadars.add(radarToAlert.numeroRadar)
                }
            }
        }
    }

    private fun alertUserProximity(radar: RadarInfoSerializable, distanceInMeters: Float) { // Corrected to kotlin.Float
        val distanceInKm = distanceInMeters / 1000f
        val alertText = "Radar ${radar.numeroRadar} à ${"%.2f".format(distanceInKm)} km!"
        Log.i(TAG, "ALERT: $alertText")

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // putExtra("highlight_radar_id", radar.numeroRadar) // Optional
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val alertNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Alerte Radar à Proximité")
            .setContentText(alertText)
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .build()

        notificationManager.notify(PROXIMITY_ALERT_NOTIFICATION_ID, alertNotification)
    }

    private fun createForegroundNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Suivi de Localisation RadarLoc"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = "Canal pour le service de suivi de localisation et les alertes radar."
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        stopLocationUpdates()
        serviceJob.cancel()
        Log.i(TAG, "Location tracking service destroyed.")
        super.onDestroy()
    }
}