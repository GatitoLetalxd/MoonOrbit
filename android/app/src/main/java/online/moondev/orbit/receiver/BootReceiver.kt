package online.moondev.orbit.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import online.moondev.orbit.data.local.PreferencesManager
import online.moondev.orbit.service.LocationForegroundService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MoonOrbitBoot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Device rebooted or package replaced: $action. Checking session to restart background service.")
            
            val pendingResult = goAsync()
            val prefs = PreferencesManager(context)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val token = prefs.sessionToken.firstOrNull()
                    val permsDone = prefs.permissionsCompleted.firstOrNull() ?: false

                    if (!token.isNullOrBlank() && permsDone) {
                        Log.d(TAG, "Active session detected. Starting LocationForegroundService automatically.")
                        val serviceIntent = Intent(context, LocationForegroundService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        Log.d(TAG, "No active session or permissions not completed. Skipping service start.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking session on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
