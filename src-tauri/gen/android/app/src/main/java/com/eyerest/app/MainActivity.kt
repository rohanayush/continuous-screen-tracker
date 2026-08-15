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
      rearm()
    }

    /**
     * Quiet hours, as a JSON array of {label, start, end, on} where start and end
     * are minutes from local midnight. An end before a start wraps past midnight,
     * which is how sleep is expressed.
     */
    @JavascriptInterface
    fun getWindows(): String =
      prefs().getString(Suppression.KEY_WINDOWS, "[]") ?: "[]"

    @JavascriptInterface
    fun setWindows(json: String) {
      Suppression.write(prefs(), json)
      // Re-arm so a window that just started takes the reminder off the table now.
      rearm()
    }

    /** Epoch ms the current snooze runs to, or "0". Set from the overlay only. */
    @JavascriptInterface
    fun getSnoozeUntil(): String = Suppression.snoozeUntil(prefs()).toString()

    /** Ending a snooze early is allowed — starting one is not. */
    @JavascriptInterface
    fun clearSnooze() {
      Suppression.clearSnooze(prefs())
      rearm()
    }

    /** Why it is currently quiet — a window label, "Snoozed", or "". */
    @JavascriptInterface
    fun quietReason(): String =
      Suppression.activeLabel(prefs(), System.currentTimeMillis()) ?: ""

    private fun rearm() {
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
