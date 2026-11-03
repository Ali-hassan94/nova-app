package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var callBtn: Button
    private lateinit var langBtn: Button
    private lateinit var keyInput: EditText

    private val langs = listOf("ur-PK" to "Urdu", "en-US" to "English", "hi-IN" to "Hindi")

    private fun prefs() = getSharedPreferences("nova", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, bg: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 16f
        val d = GradientDrawable()
        d.cornerRadius = dp(14).toFloat()
        d.setColor(bg)
        b.background = d
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(20))
        root.setBackgroundColor(Color.parseColor("#0B1020"))

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.alixora_logo)
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        val lp0 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200))
        logo.layoutParams = lp0
        root.addView(logo)

        val title = TextView(this)
        title.text = "ALIXORA AI"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.parseColor("#7DD3FC"))
        title.gravity = Gravity.CENTER
        root.addView(title)

        val sub = TextView(this)
        sub.text = "Personal AI Assistant · Ali Hassan · Aimen Shaheen"
        sub.setTextColor(Color.parseColor("#94A3B8"))
        sub.gravity = Gravity.CENTER
        root.addView(sub)

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.setPadding(0, dp(14), 0, dp(6))
        root.addView(status)

        keyInput = EditText(this)
        keyInput.hint = if (Config.hasBuiltInKey()) "Groq key (zaroori nahi, app me pehle se hai)" else "Groq API key (gsk_...)"
        keyInput.setHintTextColor(Color.GRAY)
        keyInput.setTextColor(Color.WHITE)
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyInput.setText(prefs().getString("groq_key", ""))
        root.addView(keyInput)

        root.addView(button("Key save karein", Color.parseColor("#334155")) {
            prefs().edit().putString("groq_key", keyInput.text.toString().trim()).apply()
            Toast.makeText(this, "Key save ho gayi", Toast.LENGTH_SHORT).show()
            refresh()
        })

        langBtn = button("", Color.parseColor("#334155")) {
            val cur = prefs().getString("lang", "ur-PK")
            val idx = langs.indexOfFirst { it.first == cur }
            prefs().edit().putString("lang", langs[(idx + 1) % langs.size].first).apply()
            refresh()
            if (JarvisService.running) Toast.makeText(this, "Band karke dobara shuru karein", Toast.LENGTH_SHORT).show()
        }
        root.addView(langBtn)

        root.addView(button("1) Accessibility on karein", Color.parseColor("#7C3AED")) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2) Display over other apps on karein", Color.parseColor("#7C3AED")) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        root.addView(button("3) Messages access on karein (optional)", Color.parseColor("#7C3AED")) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        callBtn = button("", Color.parseColor("#0EA5E9")) { toggleNova() }
        callBtn.textSize = 22f
        callBtn.setPadding(0, dp(18), 0, dp(18))
        root.addView(callBtn)

        logView = TextView(this)
        logView.setTextColor(Color.parseColor("#CBD5E1"))
        logView.setPadding(0, dp(12), 0, dp(24))
        root.addView(logView)

        scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B1020"))
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun toggleNova() {
        if (JarvisService.running) {
            stopService(Intent(this, JarvisService::class.java))
            refresh()
            return
        }
        if (Config.groqKey(this).isBlank()) {
            Toast.makeText(this, "Pehle Groq API key daal kar save karein", Toast.LENGTH_LONG).show()
            return
        }
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startNova()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startNova()
        } else {
            Toast.makeText(this, "Mic permission ke baghair Nova nahi chalega", Toast.LENGTH_LONG).show()
        }
    }

    private fun startNova() {
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))
        refresh()
        moveTaskToBack(true) // app background me chali jati hai, Nova chalta rehta hai
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val keyOk = Config.groqKey(this).isNotBlank()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val acc = NovaAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        status.text = "Groq key ${mark(keyOk)}   Mic ${mark(mic)}\n" +
            "Accessibility ${mark(acc)}   Overlay ${mark(overlay)}\n" +
            "Messages access ${mark(NovaNotificationListener.connected)}"
        val cur = prefs().getString("lang", "ur-PK")
        langBtn.text = "Zaban: " + (langs.firstOrNull { it.first == cur }?.second ?: "Urdu")
        callBtn.text = if (JarvisService.running) "⏹  Band karo" else "📞  Call karo"
    }

    override fun onResume() {
        super.onResume()
        JarvisService.onLog = { msg ->
            logView.append(msg + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            refresh()
        }
        refresh()
    }

    override fun onPause() {
        JarvisService.onLog = null
        super.onPause()
    }
}
