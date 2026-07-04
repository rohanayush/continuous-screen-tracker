package com.eyerest.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Foreground service that measures how long the screen has been continuously ON.
 *
 * - Screen turns ON  -> reset to 0 and start counting.
 * - After THRESHOLD_MS of continuous screen-on time -> show a full-screen black
 *   "take rest" overlay that can only be dismissed by entering a pet name.
 * - Screen turns OFF (lock) -> remove overlay and reset to 0.
 *
 * Only the current continuous session is tracked; nothing accumulates across locks.
 */
class ScreenTimerService : Service() {

    companion object {
        // ▶ TESTING: 20 seconds. For production change to 20 * 60 * 1000L (20 minutes).
        const val THRESHOLD_MS = 20_000L

        // Pet-name dismissal: entered name must match the saved one with at least
        // this LCS-based similarity (so "gldi" still matches "goldie").
        const val MATCH_THRESHOLD = 0.70

        private const val CHANNEL_ID = "eye_rest_channel"
        private const val NOTIF_ID = 1001
        private const val PREFS = "eye_rest_prefs"
        private const val KEY_PET = "pet_name"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val showReminder = Runnable { showOverlay() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startInForeground()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            startCounting()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- screen on/off ----------------------------------------------------

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> startCounting()
                    Intent.ACTION_SCREEN_OFF -> resetCounting()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    /** Screen turned on: reset to 0 and schedule the reminder. */
    private fun startCounting() {
        removeOverlay()
        handler.removeCallbacks(showReminder)
        handler.postDelayed(showReminder, THRESHOLD_MS)
    }

    /** Screen turned off / locked: cancel everything and reset to 0. */
    private fun resetCounting() {
        handler.removeCallbacks(showReminder)
        removeOverlay()
    }

    // ---- pet-name storage + fuzzy match ----------------------------------

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savedPetName(): String = prefs().getString(KEY_PET, "") ?: ""

    private fun savePetName(name: String) {
        prefs().edit().putString(KEY_PET, name).apply()
    }

    /** Longest common subsequence length (preserves character order). */
    private fun lcsLength(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp[n][m]
    }

    /** Order-preserving similarity in [0,1]: 2*LCS / (len(a)+len(b)). */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 2.0 * lcsLength(a, b) / (a.length + b.length)
    }

    // ---- full-screen rest overlay ----------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true // absorb touches; no tap-to-dismiss anymore
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val title = TextView(this).apply {
            text = "Your eyes must be tired.\nTake rest. 😴"
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val firstTime = savedPetName().isEmpty()
        val prompt = TextView(this).apply {
            text = if (firstTime)
                "Set a pet name — you'll type it to dismiss this next time."
            else
                "Enter your pet name to dismiss."
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(10))
        }

        val input = EditText(this).apply {
            hint = "pet name"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
            setBackgroundColor(Color.parseColor("#222222"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER
            isSingleLine = true
            textSize = 18f
        }

        val error = TextView(this).apply {
            setTextColor(Color.parseColor("#FF6B6B"))
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }

        val button = Button(this).apply {
            text = "Dismiss"
        }
        button.setOnClickListener {
            val entered = input.text.toString().trim()
            val saved = savedPetName()
            if (saved.isEmpty()) {
                if (entered.isEmpty()) {
                    error.text = "Enter a pet name to set."
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                savePetName(entered)
                dismiss(input)
            } else {
                if (similarity(saved.lowercase(), entered.lowercase()) >= MATCH_THRESHOLD) {
                    dismiss(input)
                } else {
                    error.text = "That doesn't match your pet name."
                    error.visibility = View.VISIBLE
                }
            }
        }

        column.addView(title)
        column.addView(prompt)
        column.addView(
            input,
            LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        column.addView(error)
        column.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(20) }
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.gravity = Gravity.CENTER }
        )

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Focusable (no FLAG_NOT_FOCUSABLE) so the EditText can take keyboard input.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        try {
            windowManager?.addView(root, params)
            overlayView = root
            handler.post {
                input.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        } catch (_: Exception) {
            overlayView = null
        }
    }

    /** Hide keyboard, remove overlay, and start a fresh session. */
    private fun dismiss(input: EditText) {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {
        }
        removeOverlay()
        startCounting()
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    // ---- foreground notification -----------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Eye Rest Reminder",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the screen-time reminder running." }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Eye Rest Reminder")
            .setContentText("Watching your continuous screen time.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(showReminder)
        removeOverlay()
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
    }
}
