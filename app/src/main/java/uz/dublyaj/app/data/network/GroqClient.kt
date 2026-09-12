package uz.dublyaj.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uz.dublyaj.app.data.model.TranscriptSegment
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Groq API bilan ishlash: nutqni matnga aylantirish (Whisper) va
 * o'zbek tiliga tarjima (chat completion). Model nomlari — mavjud,
 * ishlayotgan Python backend (services/ai_providers.py, services/ai_service.py)
 * bilan bir xil, tasodifiy tanlanmagan.
 */
class GroqClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audioFile: File): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
            )
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 413) {
                    throw Exception(
                        "Video juda uzun (audio fayli Groq yuklash chegarasidan katta). " +
                            "Hozircha qisqaroq (~20 daqiqagacha) video bilan urinib ko'ring."
                    )
                }
                throw Exception("Groq transkripsiya xatosi: HTTP ${response.code} — $bodyStr")
            }
            val json = JSONObject(bodyStr)
            val segmentsJson = json.optJSONArray("segments") ?: JSONArray()
            val result = mutableListOf<TranscriptSegment>()
            for (i in 0 until segmentsJson.length()) {
                val seg = segmentsJson.getJSONObject(i)
                val text = seg.getString("text").trim()
                if (text.isNotEmpty()) {
                    result.add(
                        TranscriptSegment(
                            start = seg.getDouble("start"),
                            end = seg.getDouble("end"),
                            text = text
                        )
                    )
                }
            }
            result
        }
    }

    suspend fun translateToUzbek(segments: List<TranscriptSegment>): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            val chunkSize = 10
            val result = mutableListOf<TranscriptSegment>()
            var i = 0

            while (i < segments.size) {
                val chunk = segments.subList(i, minOf(i + chunkSize, segments.size))
                val numbered = chunk.mapIndexed { idx, seg -> "${idx + 1}. ${seg.text}" }
                    .joinToString("\n")

                val prompt = buildString {
                    append("Sen professional kino tarjimonisan. Quyidagi raqamlangan matnlarni ")
                    append("o'zbek tiliga tabiiy va ravon tarjima qil.\n")
                    append("Qoidalar:\n")
                    append("- Har bir qatorni xuddi shunday raqam bilan boshla (1., 2., 3...).\n")
                    append("- Har bir raqam faqat BITTA qatorda bo'lsin, birlashtirma yoki bo'lib yubroma.\n")
                    append("- Nechta matn berilsa, aynan shuncha qator qaytar.\n")
                    append("- Faqat tarjimani qaytar, ortiqcha izoh yozma.\n\n")
                    append("Matnlar:\n")
                    append(numbered)
                }

                val payload = JSONObject().apply {
                    // "openai/gpt-oss-120b" — mavjud Python backendda tasdiqlangan, ishlayotgan model.
                    put("model", "openai/gpt-oss-120b")
                    put("max_tokens", 4096)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            }
                        )
                    )
                }

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val responseText = client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw Exception("Groq tarjima xatosi: HTTP ${response.code} — $bodyStr")
                    }
                    val json = JSONObject(bodyStr)
                    json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                }

                val translatedTexts = parseNumberedTranslation(responseText, chunk.size)
                chunk.forEachIndexed { idx, seg ->
                    val text = translatedTexts[idx] ?: seg.text
                    result.add(seg.copy(text = text))
                }

                i += chunkSize
            }
            result
        }

    /**
     * Python backendda (services/ai_service.py) sinovdan o'tgan xuddi shu mantiq:
     * avval raqam bo'yicha moslashtiradi, agar to'liq mos kelmasa pozitsion
     * (tartib bo'yicha) fallback qiladi — shu orqali qatorlar "tarjima qilinmagan"
     * holda asl tilda qolib ketmaydi.
     */
    private fun parseNumberedTranslation(responseText: String, expectedCount: Int): Map<Int, String> {
        val lineRegex = Regex("""^\s*(\d+)\s*[.):\-]\s*(.*)$""")
        val rawLines = responseText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val byNumber = mutableMapOf<Int, String>()
        for (line in rawLines) {
            val match = lineRegex.find(line) ?: continue
            val num = match.groupValues[1].toIntOrNull() ?: continue
            val text = match.groupValues[2].trim()
            if (num in 1..expectedCount && text.isNotEmpty()) {
                byNumber.putIfAbsent(num - 1, text)
            }
        }

        if (byNumber.size >= expectedCount) return byNumber

        val stripped = rawLines.map { line ->
            lineRegex.replace(line) { m -> m.groupValues[2] }.trim()
        }
        if (stripped.size == expectedCount) {
            return stripped.withIndex().associate { (idx, text) -> idx to text }
        }

        return byNumber
    }
}
