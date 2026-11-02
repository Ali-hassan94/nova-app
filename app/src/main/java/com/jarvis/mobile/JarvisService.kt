package com.jarvis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service: sunta hai -> Groq se poochta hai -> phone par kaam karta hai -> bolta hai -> dobara sunta hai.
 * "Call karo" dabane par shuru hota hai, jab tak band na karein chalta rehta hai.
 */
class JarvisService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var onLog: ((String) -> Unit)? = null
        const val ACTION_STOP = "com.jarvis.mobile.STOP"
        private const val CHANNEL = "nova_channel"
        private const val MODEL = "llama-3.3-70b-versatile"

        fun log(msg: String) {
            Handler(Looper.getMainLooper()).post { onLog?.invoke(msg) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var active = false
    private val history = ArrayList<JSONObject>()
    private var lang = "ur-PK"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        startInForeground()
        lang = getSharedPreferences("nova", Context.MODE_PRIVATE).getString("lang", "ur-PK") ?: "ur-PK"
        active = true
        running = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:listen").also { it.acquire() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val r = tts?.setLanguage(Locale.forLanguageTag(lang))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { listen() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { listen() }
                })
            }
            say(
                when (lang) {
                    "en-US" -> "Nova is ready. Go ahead."
                    "hi-IN" -> "नोवा तैयार है। बोलिए।"
                    else -> "نووا تیار ہے۔ بولیے۔"
                }
            )
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nova", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Nova sun raha hai")
            .setContentText("Band karne ke liye 'Band karo' dabayein")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Band karo", stop)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ---------------- sunna ----------------
    private fun listen(delayMs: Long = 0) {
        if (!active) return
        main.postDelayed({
            if (!active) return@postDelayed
            try {
                recognizer?.destroy()
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    log("Speech recognition maujood nahi. Google app / Speech Services install karein.")
                    return@postDelayed
                }
                val r = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) {
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            log("Mic permission nahi mili.")
                            stopSelf()
                            return
                        }
                        val quick = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        listen(if (quick) 200L else 1200L)
                    }
                    override fun onResults(results: Bundle?) {
                        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (t.isNullOrBlank()) listen(200L) else handle(t)
                    }
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                r.startListening(i)
            } catch (e: Exception) {
                log("mic error: ${e.message}")
                listen(2000L)
            }
        }, delayMs)
    }

    private fun handle(text: String) {
        log("Aap: $text")
        val low = text.lowercase()
        val stopWords = listOf("stop nova", "nova stop", "goodbye nova", "alvida nova", "الوداع")
        if (stopWords.any { low.contains(it) }) {
            say("Khuda hafiz")
            main.postDelayed({ stopSelf() }, 1800)
            return
        }
        io.execute {
            val reply = try {
                brain(text)
            } catch (e: Exception) {
                log("error: ${e.message}")
                "Kuch masla aa gaya, dobara bolein."
            }
            log("Nova: $reply")
            main.post { say(reply) }
        }
    }

    // ---------------- bolna ----------------
    private fun say(text: String) {
        if (!active) return
        if (!ttsReady || text.isBlank()) {
            listen()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }

    // ---------------- dimagh (Groq) ----------------
    private fun systemPrompt(): String {
        val language = when (lang) {
            "en-US" -> "English"
            "hi-IN" -> "Hindi (Devanagari script)"
            else -> "Urdu (Urdu script)"
        }
        return "You are Nova, a voice assistant that lives on the user's Android phone. You were made by Ali Hassan. " +
            "Reply in $language, in one or two short spoken sentences, no markdown, no emoji. " +
            "Use the tools to actually do things on the phone. App names must be given to tools in English as they appear on the phone (WhatsApp, YouTube, Chrome, Camera). " +
            "To work inside an app: open it with phone_open_app, then ui_read_screen to see what is on screen, then ui_click with the exact visible text, or ui_type for text input. " +
            "If a tool returns accessibility_off, tell the user to turn on the Nova accessibility service in the Nova app. " +
            "Never say a task is done unless the tool result starts with ok. " +
            "phone_sms only prepares the message; send it with ui_click only when the user clearly asked to send."
    }

    private fun brain(userText: String): String {
        val key = getSharedPreferences("nova", Context.MODE_PRIVATE).getString("groq_key", "") ?: ""
        if (key.isBlank()) return "Pehle app me Groq API key daalein."

        history.add(JSONObject().put("role", "user").put("content", userText))
        while (history.size > 12) history.removeAt(0)

        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        for (h in history) msgs.put(h)

        var answer = ""
        for (round in 0 until 6) {
            val resp = callGroq(key, msgs)
            val m = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = m.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                answer = if (m.isNull("content")) "" else m.optString("content", "").trim()
                break
            }
            msgs.put(JSONObject().put("role", "assistant").put("tool_calls", calls))
            for (k in 0 until calls.length()) {
                val c = calls.getJSONObject(k)
                val fn = c.getJSONObject("function")
                val name = fn.getString("name")
                val raw = fn.optString("arguments", "{}")
                val args = try { JSONObject(if (raw.isBlank()) "{}" else raw) } catch (e: Exception) { JSONObject() }
                log("→ $name $args")
                val result = Tools.run(this, name, args)
                log("   $result".take(200))
                msgs.put(
                    JSONObject().put("role", "tool")
                        .put("tool_call_id", c.getString("id"))
                        .put("name", name)
                        .put("content", result.take(3000))
                )
            }
        }
        if (answer.isBlank()) answer = "Kaam ho gaya."
        history.add(JSONObject().put("role", "assistant").put("content", answer))
        return answer
    }

    private fun callGroq(key: String, messages: JSONArray): JSONObject {
        val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("tools", Tools.schema)
            .put("tool_choice", "auto")
            .put("temperature", 0.3)
            .put("max_tokens", 400)
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("Groq $code: ${text.take(200)}")
        return JSONObject(text)
    }

    override fun onDestroy() {
        active = false
        running = false
        main.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (ignored: Exception) {}
        tts?.stop()
        tts?.shutdown()
        io.shutdownNow()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (ignored: Exception) {}
        log("Nova band ho gaya.")
        super.onDestroy()
    }
}
