package ai.pleos.contest.league

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// 효과음 3종 + 결과 효과음은 짧은 생성음(사인파). TTS는 Android 기본 TTS 한 문장, 내비 음성 안내 중이면 건너뜀.
class Sounds(private val ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        runCatching {
            tts = TextToSpeech(ctx) { st ->
                ttsReady = st == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.KOREAN).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
                Log.i(Engine.TAG, "TTS 준비 $ttsReady")
            }
        }.onFailure { Log.w(Engine.TAG, "TTS $it") }
    }

    fun combo(n: Int) = play(if (n >= 10) listOf(660.0, 880.0, 1100.0) else listOf(660.0, 880.0), 90)
    fun cleanKm() = play(listOf(880.0), 80)
    fun beltAllIn() = play(listOf(523.0, 659.0), 140)
    fun result() = play(listOf(523.0, 659.0, 784.0, 1047.0), 120)
    fun ladder() = play(listOf(784.0), 60)

    // 내비 음성 안내 중인가(재생 중인 소리의 용도로 판단)
    fun naviSpeaking(): Boolean = runCatching {
        am.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
    }.getOrDefault(false)

    fun say(text: String) {
        if (!ttsReady) return
        if (naviSpeaking()) { Log.i(Engine.TAG, "TTS 건너뜀(내비 안내 중): $text"); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "league")
    }

    private fun play(notes: List<Double>, noteMs: Int) = Thread {
        runCatching {
            val rate = 22050
            val n = rate * noteMs / 1000
            val buf = ShortArray(n * notes.size)
            notes.forEachIndexed { i, f ->
                for (k in 0 until n) {
                    val env = minOf(1.0, k / (rate * 0.01), (n - k) / (rate * 0.03))   // 짧은 어택과 릴리즈
                    buf[i * n + k] = (sin(2 * PI * f * k / rate) * env * 9000).toInt().toShort()
                }
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(buf, 0, buf.size); t.play()
            Thread.sleep((noteMs * notes.size + 100).toLong()); t.release()
        }.onFailure { Log.w(Engine.TAG, "효과음 $it") }
    }.start()

    fun release() { runCatching { tts?.shutdown() } }
}
