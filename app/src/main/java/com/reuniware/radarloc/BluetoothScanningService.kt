package com.reuniware.radarloc

// Dans BluetoothScanningService.kt
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context // Ajout pour getSystemService dans la création de channel
import android.content.Intent
import android.content.pm.PackageManager // Ajout pour la vérification de permission
import android.os.IBinder
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat // Pour vérifier les permissions
import androidx.core.app.NotificationCompat // Ajout pour construire la notification

class BluetoothScanningService : Service() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false

    // --- AJOUT : Constantes pour la notification de premier plan ---
    private val CHANNEL_ID = "BluetoothScanChannel"
    private val NOTIFICATION_ID = 123 // Choisissez un ID unique pour votre notification
    // --- FIN AJOUT ---


    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                // Vérifier la permission BLUETOOTH_CONNECT avant d'accéder à device.name sur Android 12+
                var deviceName = "N/A"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(this@BluetoothScanningService, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        try { deviceName = device.name ?: "Unknown Device" } catch (e: SecurityException) { Log.e("BluetoothScanService","SecurityException for device.name: ${e.message}") }
                    } else {
                        Log.w("BluetoothScanService", "BLUETOOTH_CONNECT permission not granted for device.name")
                    }
                } else {
                    try { deviceName = device.name ?: "Unknown Device" } catch (e: SecurityException) { Log.e("BluetoothScanService","SecurityException for device.name (pre-S): ${e.message}") }
                }
                Log.i("BluetoothScanService", "Device found: $deviceName - ${device.address}")
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            // Traiter les résultats en batch si le scan est configuré pour cela
            results?.forEach { result ->
                result.device?.let { device ->
                    var deviceName = "N/A"
                    // Logique similaire pour device.name que dans onScanResult
                    Log.i("BluetoothScanService", "Batch Device found: ${device.address}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BluetoothScanService", "Scan BLE failed with error code: $errorCode")
            scanning = false
            // Envisager d'arrêter le service ou de tenter un redémarrage
            // stopSelf() // ou une logique de relance plus sophistiquée
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BluetoothScanService", "Service onCreate") // Log de débogage
        // --- AJOUT : Création du canal de notification (pour Android 8.0+) ---
        createNotificationChannel()
        // --- FIN AJOUT ---

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager // Rendre safe
        if (bluetoothManager == null) {
            Log.e("BluetoothScanService", "BluetoothManager non disponible.")
            stopSelf()
            return
        }
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothScanService", "Bluetooth is not enabled. Service might not work.")
            stopSelf()
            return
        }
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e("BluetoothScanService", "BluetoothLeScanner non disponible (probablement pas de support BLE).")
            stopSelf()
            return
        }
    }

    // --- AJOUT : Méthode pour créer le canal de notification ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Scanning Service Channel", // Nom visible par l'utilisateur
                NotificationManager.IMPORTANCE_LOW // Ou autre importance, LOW est souvent bien pour les services de fond discrets
            )
            serviceChannel.description = "Channel for Bluetooth scanning service notifications" // Description optionnelle
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d("BluetoothScanService", "Notification channel created.") // Log de débogage
        }
    }
    // --- FIN AJOUT ---

    // L'annotation @RequiresPermission est bonne pour la lisibilité et l'analyse statique.
    // La vérification réelle des permissions est cruciale avant d'appeler des API protégées.
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Toast.makeText(applicationContext, "BSS:onStartCommand - Action: ${intent?.action}", Toast.LENGTH_SHORT).show()

        Log.i("BluetoothScanService", "Service onStartCommand.")

        // --- MODIFICATION : Démarrer en premier plan AVANT de faire un travail long ou de démarrer le scan ---
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scan Bluetooth Actif")
            .setContentText("Recherche d'appareils Bluetooth à proximité...")
            .setSmallIcon(R.mipmap.ic_launcher) // **IMPORTANT: Remplacez par une icône réelle de votre application drawable**
            // .setPriority(NotificationCompat.PRIORITY_LOW) // Déjà défini par l'importance du canal sur O+
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d("BluetoothScanService", "Service démarré en premier plan.") // Log de débogage
        } catch (e: Exception) {
            Log.e("BluetoothScanService", "Erreur lors du démarrage en premier plan: ${e.message}", e)
            // Si startForeground échoue (par exemple, permissions manquantes pour le service de premier plan
            // ou type de service de premier plan non déclaré sur Android 14+), le service sera arrêté.
            // Le système pourrait générer une exception différente si le type de service de premier plan est manquant sur A14+
            stopSelf()
            return START_NOT_STICKY // Ne pas essayer de redémarrer si on ne peut pas passer en FG
        }
        // --- FIN MODIFICATION ---


        // Vérifier si Bluetooth est toujours activé et si le scanner est prêt
        if (!bluetoothAdapter.isEnabled || bluetoothLeScanner == null) {
            Log.w("BluetoothScanService", "Scan non démarré: Bluetooth désactivé ou scanner non initialisé après démarrage du service.")
            stopSelf()
            return START_NOT_STICKY
        }

        startBleScan()
        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBleScan() {
        // Bonne pratique : Revérifier la permission BLUETOOTH_SCAN ici, surtout si le service peut être redémarré.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BluetoothScanService", "Permission BLUETOOTH_SCAN non accordée DANS le service. Arrêt.")
            stopSelf()
            return
        }
        // Pour les versions antérieures, la permission ACCESS_FINE_LOCATION est implicitement nécessaire pour le scan BLE,
        // et MainActivity est censée l'avoir demandée.

        if (bluetoothLeScanner == null) {
            Log.e("BluetoothScanService", "BluetoothLeScanner non initialisé.")
            stopSelf() // S'assurer que le service s'arrête s'il ne peut pas fonctionner
            return
        }

        if (!scanning) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                bluetoothLeScanner?.startScan(null, settings, leScanCallback)
                scanning = true
                Log.i("BluetoothScanService", "Scan BLE démarré.")
            } catch (se: SecurityException) {
                Log.e("BluetoothScanService", "SecurityException lors du démarrage du scan BLE: ${se.message}", se)
                stopSelf() // Arrêter si une SecurityException se produit
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Exception générique lors du démarrage du scan BLE: ${e.message}", e)
                stopSelf() // Arrêter pour d'autres exceptions aussi
            }
        } else {
            Log.i("BluetoothScanService", "Scan BLE déjà en cours.")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BluetoothScanService", "Permission BLUETOOTH_SCAN non accordée pour arrêter le scan DANS le service.")
            // Ne rien faire de plus si la permission manque, car stopScan la requiert.
            return
        }

        if (scanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                scanning = false
                Log.i("BluetoothScanService", "Scan BLE arrêté.")
            } catch (se: SecurityException) {
                Log.e("BluetoothScanService", "SecurityException lors de l'arrêt du scan BLE: ${se.message}", se)
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Exception générique lors de l'arrêt du scan BLE: ${e.message}", e)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        stopForeground(STOP_FOREGROUND_REMOVE) // S'assurer que la notification est enlevée si le service est détruit
        Log.i("BluetoothScanService", "Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}