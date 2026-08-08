package com.eyerest.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
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

  /**
   * The screen is a WebView but the timer lives in a native service, so the two
   * need one narrow channel. `window.EyeRest` lets the UI read and set the rest
   * interval; everything else stays where it is.
   */
  override fun onWebViewCreate(webView: WebView) {
    webView.addJavascriptInterface(IntervalBridge(this), "EyeRest")
  }

  class IntervalBridge(private val activity: MainActivity) {
    private fun prefs() =
      activity.getSharedPreferences(ScreenTimerService.PREFS, Context.MODE_PRIVATE)

    /** Milliseconds; returned as a String because JS numbers cross as doubles. */
    @JavascriptInterface
    fun getIntervalMs(): String =
      prefs().getLong(
        ScreenTimerService.KEY_INTERVAL,
        ScreenTimerService.DEFAULT_THRESHOLD_MS
      ).toString()

    /** The intervals on offer, as a comma-separated list of milliseconds. */
    @JavascriptInterface
    fun getChoices(): String = ScreenTimerService.INTERVAL_CHOICES.joinToString(",")

    /** Store the choice and re-arm the running service straight away. */
    @JavascriptInterface
    fun setIntervalMs(value: String) {
      val ms = value.toLongOrNull() ?: return
      if (!ScreenTimerService.INTERVAL_CHOICES.contains(ms)) return
      prefs().edit().putLong(ScreenTimerService.KEY_INTERVAL, ms).apply()

      val intent = Intent(activity, ScreenTimerService::class.java).apply {
        action = ScreenTimerService.ACTION_INTERVAL_CHANGED
      }
      ContextCompat.startForegroundService(activity, intent)
    }
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
