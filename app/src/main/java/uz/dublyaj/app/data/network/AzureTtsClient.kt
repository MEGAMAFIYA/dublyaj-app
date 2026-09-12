package uz.dublyaj.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Azure Speech (TTS) klienti. Xuddi ishlayotgan Python backend
 * (services/tts_service.py) kabi — token almashinuvisiz, to'g'ridan-to'g'ri
 * "Ocp-Apim-Subscription-Key" headeri orqali ishlaydi (soddaroq va sinovdan
 * o'tgan yondashuv).
 *
 * Farqi: Python mp3 (audio-24khz-48kbitrate-mono-mp3) so'raydi, biz esa
 * RAW PCM (raw-24khz-16bit-mono-pcm) so'raymiz — shunda telefonda alohida
 * mp3 dekoder kerak bo'lmaydi, baytlarni to'g'ridan-to'g'ri audio
 * "vaqt chizig'i"ga joylashtirish mumkin.
 */
class AzureTtsClient(private val apiKey: String, private val region: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val url = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"

    /** Natija: 24kHz, 16-bit, mono, RAW PCM baytlar (sarlavhasiz). */
    suspend fun synthesizeToPcm(text: String, voiceName: String): ByteArray = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ByteArray(0)

        val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"uz-UZ\">" +
            "<voice name=\"$voiceName\">${escapeXml(text)}</voice>" +
            "</speak>"

        val request = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", apiKey)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
            .addHeader("User-Agent", "DublyajApp")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaTypeOrNull()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw Exception("Azure TTS xatosi ($voiceName): HTTP ${response.code} — ${err.take(300)}")
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        const val SAMPLE_RATE = 24000
        const val VOICE_MALE = "uz-UZ-SardorNeural"
        const val VOICE_FEMALE = "uz-UZ-MadinaNeural"
    }
}
