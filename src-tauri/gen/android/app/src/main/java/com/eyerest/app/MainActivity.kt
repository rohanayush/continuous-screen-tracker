package com.eyerest.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    requestNotificationPermission()
    ensureOverlayPermission()
    startTimerService()
  }

  override fun onResume() {
    super.onResume()
    // Re-attempt the service start in case overlay permission was just granted.
    startTimerService()
  }

  private fun startTimerService() {
    val intent = Intent(this, ScreenTimerService::class.java)
    ContextCompat.startForegroundService(this, intent)
  }

  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
      ) {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
      }
    }
  }

  private fun ensureOverlayPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
      )
      try {
        startActivity(intent)
      } catch (_: Exception) {
      }
    }
  }
}
