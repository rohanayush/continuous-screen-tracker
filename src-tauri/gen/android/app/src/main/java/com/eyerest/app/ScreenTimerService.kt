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
import android.text.Editable
import android.text.TextWatcher
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
        // How long the screen may stay on before the reminder appears. Chosen in
        // the app and stored in prefs; 20 minutes is the usual eye-rest advice.
        const val DEFAULT_THRESHOLD_MS = 20 * 60 * 1000L

        /** The intervals the app offers, in milliseconds. */
        val INTERVAL_CHOICES = longArrayOf(
            20_000L,          // 20s — for trying it out
            5 * 60_000L,
            10 * 60_000L,
            20 * 60_000L,
            30 * 60_000L,
            60 * 60_000L
        )

        const val PREFS = "eye_rest_prefs"
        const val KEY_INTERVAL = "interval_ms"

        /** Tell a running service the interval changed, so it re-arms now. */
        const val ACTION_INTERVAL_CHANGED = "com.eyerest.app.INTERVAL_CHANGED"

        // Pet-name dismissal: entered name must match the saved one with at least
        // this LCS-based similarity (so "gldi" still matches "goldie").
        const val MATCH_THRESHOLD = 0.70

        private const val CHANNEL_ID = "eye_rest_channel"
        private const val NOTIF_ID = 1001
        private const val KEY_PET = "pet_name"

        /**
         * The note, mirrored here by the app. It is written in the WebView, whose
         * localStorage the service cannot read — and the overlay outlives the
         * Activity, so it needs its own copy.
         */
        const val KEY_NOTE = "user_note"
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
        // A changed interval should take effect now, not after the next unlock.
        if (intent?.action == ACTION_INTERVAL_CHANGED) {
            resetCounting()
        }
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
        // Read every time, so a change made in the app applies on the next cycle
        // even if the service was never restarted.
        handler.postDelayed(showReminder, thresholdMs())
    }

    /** The chosen rest interval, falling back to the default if never set. */
    private fun thresholdMs(): Long =
        prefs().getLong(KEY_INTERVAL, DEFAULT_THRESHOLD_MS)

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

    private fun savedNote(): String = prefs().getString(KEY_NOTE, "")?.trim() ?: ""

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

    /** Does what was typed pass as the saved pet name? */
    private fun matchesPetName(entered: String): Boolean {
        val saved = savedPetName()
        if (saved.isEmpty()) return false
        return similarity(saved.lowercase(), entered.trim().lowercase()) >= MATCH_THRESHOLD
    }

    // ---- full-screen rest overlay ----------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        // Quiet hours, or a snooze taken from a previous overlay. Come back one
        // full interval after it lifts rather than the instant it does —
        // otherwise clocking off at 18:00 is met with a black screen at 18:00,
        // which is the reminder ambushing you for time it agreed not to count.
        val now = System.currentTimeMillis()
        val quietUntil = Suppression.endsAt(prefs(), now)
        if (quietUntil > now) {
            handler.removeCallbacks(showReminder)
            handler.postDelayed(showReminder, (quietUntil - now) + thresholdMs())
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

        // The note you left yourself, revealed only once the pet name is typed.
        // It is the reward for stopping, not decoration on the way past: a note
        // readable at a glance would be read past at a glance.
        val noteText = savedNote()
        val noteView = TextView(this).apply {
            text = noteText
            setTextColor(Color.parseColor("#D9B783"))
            setBackgroundColor(Color.parseColor("#161616"))
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
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
                if (matchesPetName(entered)) {
                    dismiss(input)
                } else {
                    error.text = "That doesn't match your pet name."
                    error.visibility = View.VISIBLE
                }
            }
        }

        // ---- longer breaks, behind the same gate --------------------------
        //
        // Dismissing buys one more interval; these buy half an hour or an hour.
        // That is worth more, so it costs the same thing dismissing does — the
        // pet name — and the buttons stay dead until it is typed. Gating on
        // reading the name rather than on a tap is the whole point: the pause
        // has to be a decision, not a reflex.

        val locked = TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, dp(10))
        }

        val snoozeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val chips = mutableListOf<Button>()
        fun chip(label: String, ms: Long): Button = Button(this).apply {
            text = label
            isEnabled = false
            alpha = 0.35f
            setOnClickListener {
                // Re-checked here, not just when the field changed: the button is
                // the thing being trusted, so it verifies for itself.
                if (!matchesPetName(input.text.toString())) return@setOnClickListener
                Suppression.snoozeFor(prefs(), ms)
                dismiss(input)
            }
        }

        val chip30 = chip("30 min", 30 * 60_000L)
        val chip60 = chip("1 hour", 60 * 60_000L)
        chips.add(chip30)
        chips.add(chip60)

        fun refreshLock() {
            val unlocked = matchesPetName(input.text.toString())
            for (c in chips) {
                c.isEnabled = unlocked
                c.alpha = if (unlocked) 1f else 0.35f
            }
            noteView.visibility =
                if (unlocked && noteText.isNotEmpty()) View.VISIBLE else View.GONE
            locked.text = when {
                firstTime -> "Longer breaks unlock once a pet name is set."
                unlocked -> "Unlocked — or take longer:"
                else -> "🔒 Type your pet name to unlock a longer break."
            }
            locked.setTextColor(
                if (unlocked) Color.parseColor("#9BE8A8") else Color.parseColor("#888888")
            )
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshLock()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refreshLock()

        column.addView(title)
        column.addView(
            noteView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(22) }
        )
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
        column.addView(locked)
        snoozeRow.addView(
            chip30,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.rightMargin = dp(10) }
        )
        snoozeRow.addView(chip60)
        column.addView(snoozeRow)

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
