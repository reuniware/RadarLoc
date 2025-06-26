package com.reuniware.radarloc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {

    // Utiliser un SupervisorJob pour que l'échec d'une coroutine de log n'annule pas les autres
    private val loggingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val DEFAULT_LOG_FILE_NAME = "bluetooth_scan_log.txt"

    fun log(context: Context, tag: String, message: String, fileName: String = DEFAULT_LOG_FILE_NAME) {
        loggingScope.launch {
            try {
                // Utiliser le stockage interne spécifique à l'application (privé)
                val logFile = File(context.filesDir, fileName)

                // Ajouter un timestamp et le tag au message
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logLine = "$timestamp [$tag]: $message\n"

                // 'true' pour le mode append (ajouter à la fin du fichier)
                FileWriter(logFile, true).use { fw ->
                    BufferedWriter(fw).use { bw ->
                        bw.write(logLine)
                    }
                }
            } catch (e: IOException) {
                // En cas d'erreur d'écriture, logger dans Logcat
                Log.e("FileLogger", "Failed to write to log file '$fileName': ${e.message}", e)
            } catch (e: Exception) {
                // Attraper d'autres exceptions potentielles
                Log.e("FileLogger", "An unexpected error occurred in FileLogger: ${e.message}", e)
            }
        }
    }

    /**
     * Optionnel: Fonction pour récupérer le contenu du fichier log (pour débogage ou affichage)
     * Attention: À utiliser avec prudence pour les gros fichiers, peut bloquer si appelé sur le thread UI.
     */
    suspend fun readLogFile(context: Context, fileName: String = DEFAULT_LOG_FILE_NAME): String {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val logFile = File(context.filesDir, fileName)
            if (logFile.exists()) {
                try {
                    logFile.readText()
                } catch (e: IOException) {
                    Log.e("FileLogger", "Failed to read log file '$fileName': ${e.message}", e)
                    "Error reading log file: ${e.message}"
                }
            } else {
                "Log file '$fileName' does not exist."
            }
        }
    }

    /**
     * Optionnel: Fonction pour effacer le fichier log.
     */
    fun clearLogFile(context: Context, fileName: String = DEFAULT_LOG_FILE_NAME) {
        loggingScope.launch {
            val logFile = File(context.filesDir, fileName)
            if (logFile.exists()) {
                try {
                    if (logFile.delete()) {
                        Log.i("FileLogger", "Log file '$fileName' cleared.")
                    } else {
                        Log.w("FileLogger", "Failed to clear log file '$fileName'.")
                    }
                } catch (e: SecurityException) {
                    Log.e("FileLogger", "SecurityException when trying to clear log file '$fileName': ${e.message}", e)
                }
            }
        }
    }
}