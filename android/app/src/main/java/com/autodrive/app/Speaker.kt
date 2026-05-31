package com.autodrive.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/** เล่นเสียงแจ้งเตือน: พูดภาษาไทย (TTS) + บี๊บ พร้อม cooldown + ปรับระดับเสียง */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    private var ready = false
    private var thai = false
    private var beepVol = 90
    private var tone = ToneGenerator(AudioManager.STREAM_MUSIC, beepVol)
    private val lastSpoken = HashMap<String, Long>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("th", "TH"))
            thai = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!thai) tts.setLanguage(Locale.US)
            ready = true
        }
    }

    private fun applyBeepVolume(volume: Float) {
        val v = (volume * 100).toInt().coerceIn(1, 100)
        if (v != beepVol) {
            try { tone.release() } catch (_: Exception) {}
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, v)
            beepVol = v
        }
    }

    /**
     * แจ้งเตือน 1 ครั้งต่อ cooldown ต่อ key
     * @param th ไทย, @param en อังกฤษ (เผื่อไม่มีเสียงไทย), @param volume 0.0–1.0
     */
    fun alert(key: String, th: String, en: String, voice: Boolean, beep: Boolean, cooldownMs: Long, volume: Float) {
        val now = System.currentTimeMillis()
        if (now - (lastSpoken[key] ?: 0L) < cooldownMs) return
        lastSpoken[key] = now
        if (beep) {
            applyBeepVolume(volume)
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } catch (_: Exception) {}
        }
        if (voice && ready) {
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
            tts.speak(if (thai) th else en, TextToSpeech.QUEUE_FLUSH, params, key)
        }
    }

    /** ทดสอบเสียง (เล่นทันที ไม่มี cooldown) */
    fun test(beep: Boolean, volume: Float) {
        alert("__test", "ทดสอบเสียงแจ้งเตือน ไฟแดงข้างหน้า เตรียมหยุด",
            "Test alert. Red light ahead, prepare to stop.", true, beep, 0L, volume)
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        try { tone.release() } catch (_: Exception) {}
    }
}
