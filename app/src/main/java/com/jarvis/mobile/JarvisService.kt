package com.jarvis.mobile

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service. Mic seedha record hota hai (koi beep nahi), awaaz Groq Whisper se text banti hai,
 * Groq ka AI jawab/kaam tay karta hai, phone par kaam hota hai, Nova bolta hai, phir dobara sunta hai.
 */
class JarvisService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var onLog: ((String) -> Unit)? = null
        const val ACTION_STOP = "com.jarvis.mobile.STOP"
        private const val CHANNEL = "nova_channel"

        fun log(msg: String) {
            Handler(Looper.getMainLooper()).post { onLog?.invoke(msg) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var active = false
    @Volatile private var busy = true // true = abhi sun nahi rahe (bol rahe ya soch rahe hain)
    private val history = ArrayList<JSONObject>()
    private var lang = "ur-PK"
    private var llmModel: String? = null
    private var sttModel: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        startInForeground()
        lang = Config.prefs(this).getString("lang", "ur-PK") ?: "ur-PK"
        active = true
        running = true
        busy = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:listen").also { it.acquire() }

        Thread { recordLoop() }.start()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val r = tts?.setLanguage(Locale.forLanguageTag(lang))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { resume(500) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { resume(500) }
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

    private fun resume(delayMs: Long) {
        main.postDelayed({ if (active) busy = false }, delayMs)
    }

    // ---------------- sunna (AudioRecord + khamoshi pakadna) ----------------
    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        val sr = 16000
        val frameSize = sr / 10 // 100 ms
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frameSize * 8)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log("Mic shuru nahi hua. Mic permission check karein.")
            main.post { stopSelf() }
            return
        }
        rec.startRecording()
        val frame = ShortArray(frameSize)
        val pre = java.util.ArrayDeque<ShortArray>()
        val pcm = ByteArrayOutputStream()
        var speaking = false
        var silentMs = 0
        var speechMs = 0
        var noise = 200.0
        try {
            while (active) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                if (busy) {
                    speaking = false; silentMs = 0; speechMs = 0
                    pcm.reset(); pre.clear()
                    continue
                }
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i].toDouble()
                val rms = Math.sqrt(sum / n)
                val trigger = maxOf(450.0, noise * 3.0)
                if (rms > trigger) {
                    if (!speaking) {
                        speaking = true
                        for (p in pre) writePcm(pcm, p, p.size)
                        pre.clear()
                    }
                    silentMs = 0
                    speechMs += 100
                    writePcm(pcm, frame, n)
                } else if (!speaking) {
                    noise = noise * 0.95 + rms * 0.05
                    pre.add(frame.copyOf(n))
                    while (pre.size > 3) pre.poll()
                } else {
                    silentMs += 100
                    writePcm(pcm, frame, n)
                }
                if (speaking && (silentMs >= 1100 || speechMs + silentMs >= 15000)) {
                    val data = pcm.toByteArray()
                    val enough = speechMs >= 400
                    speaking = false; silentMs = 0; speechMs = 0
                    pcm.reset(); pre.clear()
                    if (enough) {
                        busy = true
                        val wav = wavOf(data, sr)
                        io.execute { process(wav) }
                    }
                }
            }
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
        }
    }

    private fun writePcm(out: ByteArrayOutputStream, buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val v = buf[i].toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
    }

    private fun wavOf(pcm: ByteArray, sr: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size)
        out.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(sr); i32(sr * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    // ---------------- samajhna aur kaam ----------------
    private fun process(wav: ByteArray) {
        try {
            val key = Config.groqKey(this)
            if (key.isBlank()) {
                main.post { say("Pehle Groq API key daalein.") }
                return
            }
            ensureModels(key)
            val text = transcribe(key, wav).trim()
            val plain = text.lowercase().trim(' ', '.', '!', '?', ',')
            val junk = setOf("", "you", "thank you", "thanks for watching", "bye", "okay", "ok")
            if (plain.length < 2 || plain in junk) {
                main.post { resume(200) }
                return
            }
            log("Aap: $text")
            val stopWords = listOf("stop nova", "nova stop", "اسٹاپ نووا", "نووا اسٹاپ", "الوداع", "خدا حافظ", "goodbye nova")
            if (stopWords.any { plain.contains(it) }) {
                main.post {
                    say("Khuda hafiz")
                    main.postDelayed({ stopSelf() }, 2000)
                }
                return
            }
            val answer = brain(key, text)
            log("Nova: $answer")
            main.post { say(answer) }
        } catch (e: Exception) {
            log("error: ${e.message}")
            main.post { say("Kuch masla aa gaya, dobara bolein.") }
        }
    }

    private fun say(text: String) {
        if (!active) return
        busy = true
        if (!ttsReady || text.isBlank()) {
            resume(300)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }

    // ---------------- Groq ----------------
    private fun ensureModels(key: String) {
        if (llmModel != null && sttModel != null) return
        val c = URL("https://api.groq.com/openai/v1/models").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer $key")
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("Groq $code: ${text.take(160)}")
        val arr = JSONObject(text).getJSONArray("data")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        llmModel = listOf(
            "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b",
            "meta-llama/llama-4-scout-17b-16e-instruct", "qwen/qwen3-32b", "llama-3.1-8b-instant"
        ).firstOrNull { it in ids }
            ?: ids.firstOrNull { id -> listOf("whisper", "guard", "tts", "orpheus", "compound").none { id.contains(it) } }
            ?: throw RuntimeException("Koi chat model nahi mila")
        sttModel = listOf("whisper-large-v3-turbo", "whisper-large-v3").firstOrNull { it in ids }
            ?: throw RuntimeException("Whisper model nahi mila")
        log("Model: $llmModel / $sttModel")
    }

    private fun transcribe(key: String, wav: ByteArray): String {
        val boundary = "----nova" + System.currentTimeMillis()
        val c = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val out = DataOutputStream(c.outputStream)
        fun field(name: String, value: String) {
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        field("model", sttModel ?: "whisper-large-v3-turbo")
        field("response_format", "json")
        field("temperature", "0")
        field("language", lang.substringBefore('-'))
        out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
        out.write(wav)
        out.writeBytes("\r\n--$boundary--\r\n")
        out.flush()
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("Whisper $code: ${text.take(160)}")
        return JSONObject(text).optString("text", "")
    }

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

    private fun brain(key: String, userText: String): String {
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
        val model = llmModel ?: throw RuntimeException("model not ready")
        val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", Tools.schema)
            .put("tool_choice", "auto")
            .put("temperature", 0.3)
            .put("max_tokens", 1024)
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            if (code == 404) llmModel = null
            throw RuntimeException("Groq $code: ${text.take(200)}")
        }
        return JSONObject(text)
    }

    override fun onDestroy() {
        active = false
        running = false
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        io.shutdownNow()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        log("Nova band ho gaya.")
        super.onDestroy()
    }
}
