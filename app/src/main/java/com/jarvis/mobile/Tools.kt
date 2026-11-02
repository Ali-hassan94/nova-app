package com.jarvis.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** Phone ke tools: AI inhein call karta hai (phone_*, ui_*). */
object Tools {

    // ---------- schema (Groq / OpenAI tools format) ----------
    private fun s(n: String, d: String) = "\"$n\":{\"type\":\"string\",\"description\":\"$d\"}"
    private fun i(n: String, d: String) = "\"$n\":{\"type\":\"integer\",\"description\":\"$d\"}"
    private fun b(n: String, d: String) = "\"$n\":{\"type\":\"boolean\",\"description\":\"$d\"}"
    private fun e(n: String, d: String, vararg v: String): String {
        val vals = v.joinToString(",") { "\"" + it + "\"" }
        return "\"$n\":{\"type\":\"string\",\"description\":\"$d\",\"enum\":[$vals]}"
    }

    private fun t(name: String, desc: String, props: String = "", required: String = "[]"): JSONObject =
        JSONObject("{\"type\":\"function\",\"function\":{\"name\":\"$name\",\"description\":\"$desc\",\"parameters\":{\"type\":\"object\",\"properties\":{$props},\"required\":$required}}}")

    val schema: JSONArray by lazy {
        JSONArray().apply {
            put(t("phone_open_app", "Open an app on the phone by its English name, for example WhatsApp, YouTube, Chrome, Camera, Settings", s("name", "App name"), "[\"name\"]"))
            put(t("phone_open_url", "Open a web address or a YouTube search link on the phone", s("url", "Full https address"), "[\"url\"]"))
            put(t("phone_call", "Call a contact name or a phone number directly", s("target", "Contact name or number"), "[\"target\"]"))
            put(t("phone_sms", "Open the SMS compose screen with a prepared text", s("target", "Contact name or number") + "," + s("text", "Message text"), "[\"target\",\"text\"]"))
            put(t("phone_flashlight", "Turn the flashlight on or off", b("on", "true for on, false for off"), "[\"on\"]"))
            put(t("phone_alarm", "Set an alarm for a clock time in 24 hour format", i("hour", "0-23") + "," + i("minute", "0-59") + "," + s("label", "Optional label"), "[\"hour\",\"minute\"]"))
            put(t("phone_timer", "Start a countdown timer", i("seconds", "Length in seconds"), "[\"seconds\"]"))
            put(t("phone_volume", "Change the media volume", e("action", "What to do", "up", "down", "mute", "unmute", "set") + "," + i("level", "0-100, only for set"), "[\"action\"]"))
            put(t("phone_media", "Control music or video playback", e("action", "What to do", "play_pause", "next", "previous"), "[\"action\"]"))
            put(t("phone_navigate", "Open maps for a place or address", s("place", "Place or address"), "[\"place\"]"))
            put(t("phone_settings", "Open a settings page", e("page", "Which page", "wifi", "bluetooth", "display", "sound", "battery", "airplane", "general"), "[\"page\"]"))
            put(t("ui_read_screen", "Read the visible texts on the current phone screen. Use this before clicking inside an app"))
            put(t("ui_click", "Click a button or item on the current screen by its visible text", s("text", "Visible text or description"), "[\"text\"]"))
            put(t("ui_type", "Type text into the focused or first input field on the current screen", s("text", "Text to type"), "[\"text\"]"))
            put(t("ui_scroll", "Scroll the current screen", e("direction", "Direction", "up", "down"), "[\"direction\"]"))
            put(t("ui_press", "Press a system key", e("key", "Which key", "back", "home", "recents", "notifications"), "[\"key\"]"))
        }
    }

    // ---------- execution ----------
    fun run(ctx: Context, name: String, a: JSONObject): String {
        return try {
            when (name) {
                "phone_open_app" -> openApp(ctx, a.optString("name"))
                "phone_open_url" -> {
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))))
                    Thread.sleep(1500); "ok: url opened"
                }
                "phone_call" -> call(ctx, a.optString("target"))
                "phone_sms" -> sms(ctx, a.optString("target"), a.optString("text"))
                "phone_flashlight" -> flashlight(ctx, a.optBoolean("on", true))
                "phone_alarm" -> {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Nova"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    launch(ctx, intent); "ok: alarm set"
                }
                "phone_timer" -> {
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    launch(ctx, intent); "ok: timer started"
                }
                "phone_volume" -> volume(ctx, a.optString("action"), a.optInt("level", 50))
                "phone_media" -> media(ctx, a.optString("action"))
                "phone_navigate" -> {
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.optString("place")))))
                    "ok: maps opened"
                }
                "phone_settings" -> {
                    val action = when (a.optString("page")) {
                        "wifi" -> Settings.ACTION_WIFI_SETTINGS
                        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                        "display" -> Settings.ACTION_DISPLAY_SETTINGS
                        "sound" -> Settings.ACTION_SOUND_SETTINGS
                        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                        "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                        else -> Settings.ACTION_SETTINGS
                    }
                    launch(ctx, Intent(action)); "ok: settings opened"
                }
                "ui_read_screen" -> NovaAccessibilityService.instance?.readScreen() ?: "error: accessibility_off"
                "ui_click" -> {
                    val svc = NovaAccessibilityService.instance ?: return "error: accessibility_off"
                    if (svc.clickText(a.optString("text"))) { Thread.sleep(700); "ok: clicked" } else "error: not found on screen"
                }
                "ui_type" -> {
                    val svc = NovaAccessibilityService.instance ?: return "error: accessibility_off"
                    if (svc.typeText(a.optString("text"))) "ok: typed" else "error: no input field found"
                }
                "ui_scroll" -> {
                    val svc = NovaAccessibilityService.instance ?: return "error: accessibility_off"
                    if (svc.scroll(a.optString("direction") == "down")) "ok: scrolled" else "error: nothing to scroll"
                }
                "ui_press" -> {
                    val svc = NovaAccessibilityService.instance ?: return "error: accessibility_off"
                    if (svc.press(a.optString("key"))) { Thread.sleep(500); "ok: pressed" } else "error: unknown key"
                }
                else -> "error: unknown tool $name"
            }
        } catch (ex: Exception) {
            "error: ${ex.javaClass.simpleName} ${ex.message}"
        }
    }

    /** Activity kholne ke liye accessibility service ka context use karte hain (background me behtar chalta hai). */
    private fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = NovaAccessibilityService.instance ?: ctx
        c.startActivity(intent)
    }

    private fun openApp(ctx: Context, name: String): String {
        val q = name.lowercase().trim()
        if (q.isEmpty()) return "error: no app name"
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        fun label(r: android.content.pm.ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        val best = apps.firstOrNull { label(it) == q }
            ?: apps.firstOrNull { label(it).contains(q) }
            ?: apps.firstOrNull { label(it).length > 2 && q.contains(label(it)) }
            ?: return "error: app '$name' not found"
        val li = pm.getLaunchIntentForPackage(best.activityInfo.packageName) ?: return "error: cannot launch"
        launch(ctx, li)
        Thread.sleep(1800)
        return "ok: opened ${best.loadLabel(pm)}"
    }

    private fun resolveNumber(ctx: Context, target: String): String? {
        if (target.none { it.isLetter() } && target.count { it.isDigit() } >= 5) {
            return target.filter { it.isDigit() || it == '+' }
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$target%"), null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun call(ctx: Context, target: String): String {
        val num = resolveNumber(ctx, target) ?: return "error: contact '$target' not found (give a number)"
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        launch(ctx, Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$num")))
        return if (canCall) "ok: calling $num" else "ok: dialer opened, user must press call"
    }

    private fun sms(ctx: Context, target: String, text: String): String {
        val num = resolveNumber(ctx, target) ?: ""
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", text)
        launch(ctx, i)
        Thread.sleep(1200)
        return "ok: message screen opened with text; it is NOT sent yet (use ui_click Send only if the user asked to send)"
    }

    private fun flashlight(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "error: no flashlight"
        cm.setTorchMode(id, on)
        return if (on) "ok: flashlight on" else "ok: flashlight off"
    }

    private fun volume(ctx: Context, action: String, level: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val st = AudioManager.STREAM_MUSIC
        when (action) {
            "up" -> am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val max = am.getStreamMaxVolume(st)
                am.setStreamVolume(st, (level.coerceIn(0, 100) * max) / 100, AudioManager.FLAG_SHOW_UI)
            }
            else -> return "error: unknown volume action"
        }
        return "ok: volume $action"
    }

    private fun media(ctx: Context, action: String): String {
        val code = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "error: unknown media action"
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "ok: media $action"
    }
}
