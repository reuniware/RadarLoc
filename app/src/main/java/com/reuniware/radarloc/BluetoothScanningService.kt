package com.reuniware.radarloc

// Dans BluetoothScanningService.kt
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context // Ajout pour getSystemService dans la création de channel
import android.content.Intent
import android.content.pm.PackageManager // Ajout pour la vérification de permission
import android.os.IBinder
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.size
import androidx.core.app.ActivityCompat // Pour vérifier les permissions
import androidx.core.app.NotificationCompat // Ajout pour construire la notification
import kotlin.math.pow

//import androidx.wear.compose.foundation.size

class BluetoothScanningService : Service() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false

    private val CHANNEL_ID = "BluetoothScanChannel"
    private val NOTIFICATION_ID = 123

    // Ensemble pour garder une trace des adresses MAC déjà loguées dans cette session de scan
    // pour éviter de spammer le log avec des rapports répétitifs pour le même appareil.
    // Vous pouvez le réinitialiser au début de chaque scan si nécessaire.
    // Ensemble pour garder une trace des adresses MAC déjà loguées DANS CETTE SESSION DE SCAN
    private val loggedDevicesInThisScanSession = mutableSetOf<String>()

    /**
     * Puissance de transmission de référence à 1 mètre.
     * Cette valeur est typique pour de nombreux beacons BLE, mais l'idéal est de la calibrer
     * pour l'appareil spécifique que vous suivez, si possible.
     * Une valeur courante est autour de -59 dBm à -65 dBm.
     */
    private val TX_POWER_AT_1_METER = -59 // dBm (À AJUSTER/CALIBRER SI POSSIBLE)

    /**
     * Facteur environnemental (N). Varie généralement entre 2 (espace libre) et 4 (environnement obstrué).
     * Une valeur de 2.0 est un point de départ.
     */
    private val ENVIRONMENTAL_FACTOR_N = 2.0 // (À AJUSTER/CALIBRER SI POSSIBLE)

    /**
     * Calcule la distance approximative en mètres.
     * @param rssi La force du signal reçu en dBm.
     * @param txPower La puissance de transmission de référence de l'appareil à 1 mètre en dBm.
     * @return La distance estimée en mètres.
     */
    private fun calculateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0) {
            return -1.0 // Indicateur d'une valeur RSSI invalide ou non disponible
        }
        // Formule de base pour la distance basée sur RSSI et TxPower
        // distance = 10 ^ ((txPower - RSSI) / (10 * N))
        val ratio = (txPower - rssi) / (10 * ENVIRONMENTAL_FACTOR_N)
        return 10.0.pow(ratio)
    }
    // --- FIN DES AJOUTS POUR LE CALCUL DE DISTANCE ---

    @SuppressLint("MissingPermission") // Les permissions sont vérifiées avant d'appeler les fonctions de scan
    private val leScanCallback = object : ScanCallback() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { scanResult ->
                val device = scanResult.device
                val deviceAddress = device.address

                // Récupérer le RSSI avant la condition pour pouvoir l'utiliser dans le 'else' aussi
                val rssi = scanResult.rssi

                // Vérifier si l'appareil a déjà été logué dans CETTE session de scan
                // .add() retourne true si l'élément n'était pas déjà dans le set (et l'ajoute)
                // Donc, nous procédons seulement si l'appareil est nouveau pour cette session.
                if (loggedDevicesInThisScanSession.add(deviceAddress)) {
                    var deviceName = "N/A"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                this@BluetoothScanningService,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            try {
                                deviceName = device.name ?: "Unknown Device"
                            } catch (e: SecurityException) {
                                Log.e("BluetoothScanService", "SecurityException for device.name: ${e.message}")
                                // Pas besoin de FileLogger ici car on ne loguera pas cet appareil si exception
                            }
                        } else {
                            Log.w("BluetoothScanService", "BLUETOOTH_CONNECT permission not granted for device.name")
                        }
                    } else {
                        try {
                            deviceName = device.name ?: "Unknown Device"
                        } catch (e: SecurityException) {
                            Log.e("BluetoothScanService", "SecurityException for device.name (pre-S): ${e.message}")
                        }
                    }

                    // --- AJOUT/MODIFICATION : CALCUL ET FORMATAGE DE LA DISTANCE ---
                    val estimatedDistance = calculateDistance(rssi, TX_POWER_AT_1_METER)
                    val distanceString = if (estimatedDistance > 0) String.format("%.2f m", estimatedDistance) else "N/A"
                    // --- FIN AJOUT/MODIFICATION ---


                    // Modifiez légèrement le log de TxPower pour être plus informatif
                    val txPowerFromScan = scanResult.txPower
                    val txPowerString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && txPowerFromScan != ScanResult.TX_POWER_NOT_PRESENT) {
                        "$txPowerFromScan dBm (Actual)"
                    } else {
                        "N/A (Using default: $TX_POWER_AT_1_METER dBm for distance calc)"
                    }

                    val advertisingSidString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (scanResult.advertisingSid != ScanResult.SID_NOT_PRESENT) {
                            scanResult.advertisingSid.toString()
                        } else {
                            "N/A"
                        }
                    } else {
                        "N/A"
                    }

                    val dataStatusString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        when (scanResult.dataStatus) {
                            ScanResult.DATA_COMPLETE -> "Complete"
                            ScanResult.DATA_TRUNCATED -> "Truncated"
                            else -> "N/A" // Ou scanResult.dataStatus.toString() si vous voulez le nombre brut
                        }
                    } else {
                        "N/A"
                    }

                    val primaryPhyString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        when (scanResult.primaryPhy) {
                            BluetoothDevice.PHY_LE_1M -> "LE 1M"
                            BluetoothDevice.PHY_LE_2M -> "LE 2M"
                            BluetoothDevice.PHY_LE_CODED -> "LE Coded"
                            else -> "N/A" // Ou scanResult.primaryPhy.toString()
                        }
                    } else {
                        "N/A"
                    }

                    val secondaryPhyString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        when (scanResult.secondaryPhy) {
                            BluetoothDevice.PHY_LE_1M -> "LE 1M"
                            BluetoothDevice.PHY_LE_2M -> "LE 2M"
                            BluetoothDevice.PHY_LE_CODED -> "LE Coded"
                            0 -> "Unused" // PHY_UNUSED est la valeur par défaut si non spécifié
                            else -> "N/A" // Ou scanResult.secondaryPhy.toString()
                        }
                    } else {
                        "N/A"
                    }

                    val scanRecordBytes = scanResult.scanRecord?.bytes
                    val scanRecordHexString = scanRecordBytes?.joinToString(separator = "") { String.format("%02X", it) } ?: "N/A"


                    val logDetailsForFile = """
    Device Newly Discovered in Session:
      Name: $deviceName
      Address: $deviceAddress
      RSSI: $rssi dBm
      Estimated Distance: $distanceString 
      TX Power: $txPowerString
      Advertising SID: $advertisingSidString
      Data Status: $dataStatusString
      Primary PHY: $primaryPhyString
      Secondary PHY: $secondaryPhyString
      Scan Record (Hex): $scanRecordHexString
      Timestamp Nanos: ${scanResult.timestampNanos}
    """.trimIndent()


                    // Logguer dans Logcat
                    Log.i("BluetoothScanService", "Newly Discovered: $deviceName, Address: $deviceAddress, RSSI: $rssi")

                    // Logguer dans le fichier
                    FileLogger.log(applicationContext, "BluetoothScanService", logDetailsForFile)

                    // Le Toast pourrait aussi être conditionnel pour ne pas spammer
                    Toast.makeText(
                        applicationContext,
                        "BSS: New: $deviceAddress / $deviceName",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {
                    // Optionnel: Logguer que l'appareil a été revu, mais pas les détails complets
                    // Log.v("BluetoothScanService", "Device seen again (not logging details): $deviceAddress")
                    // Ou ne rien faire du tout si on veut juste éviter les logs répétitifs.
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            val batchSize = results?.size ?: 0
            if (batchSize > 0) { // Ne loguer que s'il y a des résultats
                val batchLogMessage = "Batch Scan Results Received: $batchSize devices"
                Log.d("BluetoothScanService", batchLogMessage)
                FileLogger.log(applicationContext, "BluetoothScanService", batchLogMessage)

                results?.forEach { result ->
                    result.device?.let { device ->
                        if (loggedDevicesInThisScanSession.add(device.address)) { // Appliquer la même logique ici
                            // ... (logique pour obtenir deviceName, etc. pour les résultats de batch) ...
                            var deviceNameForBatch = "N/A" // Adaptez votre logique
                            // ...
                            val batchDeviceDetails = "Batch New Device: Name: $deviceNameForBatch, Address: ${device.address}, RSSI: ${result.rssi}"
                            Log.i("BluetoothScanService", batchDeviceDetails)
                            FileLogger.log(applicationContext, "BluetoothScanService", batchDeviceDetails)
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            var errorText = "Unknown Error"
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> errorText = "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> errorText = "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> errorText = "SCAN_FAILED_INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> errorText = "SCAN_FAILED_FEATURE_UNSUPPORTED"
                // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (API 26+)
                5 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) errorText = "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
                // SCANNING_TOO_FREQUENTLY (API 30+)
                6 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) errorText = "SCANNING_TOO_FREQUENTLY"
            }
            Log.e("BluetoothScanService", "Scan BLE failed with error code: $errorCode ($errorText)")
            scanning = false
            // stopSelf() // ou une logique de relance plus sophistiquée
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BluetoothScanService", "Service onCreate")
        createNotificationChannel()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        if (bluetoothManager == null) {
            Log.e("BluetoothScanService", "BluetoothManager non disponible.")
            stopSelf()
            return
        }
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothScanService", "Bluetooth is not enabled. Service might not work.")
            // Envisager de ne pas arrêter le service ici, mais de ne pas démarrer le scan.
            // Le service pourrait attendre que le Bluetooth soit activé.
            // Pour l'instant, on garde stopSelf() pour la simplicité.
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Scanning Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.description = "Channel for Bluetooth scanning service notifications"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d("BluetoothScanService", "Notification channel created.")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Toast.makeText(applicationContext, "BSS :onStartCommand - Action: ${intent?.action}", Toast.LENGTH_SHORT).show()
        Log.i("BluetoothScanService", "Service onStartCommand.")

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scan Bluetooth Actif")
            .setContentText("Recherche d'appareils Bluetooth à proximité...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d("BluetoothScanService", "Service démarré en premier plan.")
        } catch (e: Exception) {
            Log.e("BluetoothScanService", "Erreur lors du démarrage en premier plan: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!bluetoothAdapter.isEnabled || bluetoothLeScanner == null) {
            Log.w("BluetoothScanService", "Scan non démarré: Bluetooth désactivé ou scanner non initialisé après démarrage du service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return START_NOT_STICKY
        }
        startBleScan()
        return START_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.e("BluetoothScanService", "Permission BLUETOOTH_SCAN non accordée. Arrêt.")
                stopSelf()
                return
            }
            // BLUETOOTH_CONNECT est vérifiée dans le callback pour device.name,
            // mais startScan lui-même n'a pas besoin de BLUETOOTH_CONNECT.
            // Cependant, si vous avez des filtres basés sur le nom, vous pourriez avoir besoin de CONNECT ici aussi.
        }
        // Pour les versions antérieures, ACCESS_FINE_LOCATION est implicitement nécessaire.

        if (bluetoothLeScanner == null) {
            Log.e("BluetoothScanService", "BluetoothLeScanner non initialisé.")
            stopSelf()
            return
        }

        if (!scanning) {
            // Réinitialiser la liste des appareils logués pour cette nouvelle session de scan
            // loggedDevicesInSession.clear()

            val scanFilters: MutableList<ScanFilter> = ArrayList()
            // Exemple de filtre (optionnel) - par exemple, si vous ne voulez que certains services
            // val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(" VOTRE_SERVICE_UUID ")).build()
            // scanFilters.add(filter)

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Ou SCAN_MODE_LOW_POWER / SCAN_MODE_BALANCED
                // .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // Par défaut
                // .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) // Ou MATCH_MODE_STICKY
                // .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT) // Ou MATCH_NUM_FEW_ADVERTISEMENT / MATCH_NUM_MAX_ADVERTISEMENT
                .setReportDelay(0) // 0 pour des résultats immédiats (pas de batching), > 0 pour le batching en millisecondes
                .build()

            try {
                // Utiliser la surcharge de startScan qui prend des filtres (même une liste vide)
                bluetoothLeScanner?.startScan(scanFilters, settings, leScanCallback)
                scanning = true
                Log.i("BluetoothScanService", "Scan BLE démarré avec les paramètres: ${settings.toString()}. Filtres: ${scanFilters.size}")
            } catch (se: SecurityException) {
                Log.e("BluetoothScanService", "SecurityException lors du démarrage du scan BLE: ${se.message}", se)
                stopSelf()
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Exception générique lors du démarrage du scan BLE: ${e.message}", e)
                stopSelf()
            }
        } else {
            Log.i("BluetoothScanService", "Scan BLE déjà en cours.")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BluetoothScanService", "Permission BLUETOOTH_SCAN non accordée pour arrêter le scan.")
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i("BluetoothScanService", "Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}