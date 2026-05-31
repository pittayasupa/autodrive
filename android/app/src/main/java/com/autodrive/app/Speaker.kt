package com.autodrive.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** เล่นเสียงแจ้งเตือน: พูดภาษาไทย (TTS) + บี๊บ พร้อม cooldown ต่อชนิดเตือน */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    private var ready = false
    private var thai = false
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    private val lastSpoken = HashMap<String, Long>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("th", "TH"))
            thai = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!thai) tts.setLanguage(Locale.US)   // ไม่มีเสียงไทย -> ใช้อังกฤษ
            ready = true
        }
    }

    /**
     * แจ้งเตือน 1 ครั้งต่อ cooldown ต่อ key
     * @param th ข้อความไทย, @param en ข้อความอังกฤษ (เผื่ออุปกรณ์ไม่มีเสียงไทย)
     */
    fun alert(key: String, th: String, en: String, voice: Boolean, beep: Boolean, cooldownMs: Long) {
        val now = System.currentTimeMillis()
        if (now - (lastSpoken[key] ?: 0L) < cooldownMs) return
        lastSpoken[key] = now
        if (beep) try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } catch (_: Exception) {}
        if (voice && ready) tts.speak(if (thai) th else en, TextToSpeech.QUEUE_FLUSH, null, key)
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        try { tone.release() } catch (_: Exception) {}
    }
}
