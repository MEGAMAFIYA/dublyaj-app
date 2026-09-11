package uz.dublyaj.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Bitta yuklab olinadigan model haqida ma'lumot.
 *
 * comingSoon = true bo'lsa, hali yuklab bo'lmaydi (keyingi bosqichda qo'shiladi) —
 * bu holatda url null bo'lishi mumkin.
 */
data class ModelInfo(
    val id: String,
    val title: String,
    val description: String,
    val url: String?,
    val approxSizeMb: Int,
    val comingSoon: Boolean = false
)

object ModelCatalog {
    // Manba: ggerganov/whisper.cpp rasmiy modellari (huggingface).
    // Bular haqiqiy, ishlaydigan fayllar — hoziroq yuklab olsa bo'ladi.
    val models = listOf(
        ModelInfo(
            id = "whisper-base",
            title = "Whisper — Base (nutq tanish)",
            description = "Oflayn nutqni matnga aylantirish uchun. Tez, o'rtacha aniqlik.",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            approxSizeMb = 142
        ),
        ModelInfo(
            id = "whisper-small",
            title = "Whisper — Small (nutq tanish, aniqroq)",
            description = "Oflayn nutqni matnga aylantirish uchun. Sekinroq, ancha aniqroq.",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            approxSizeMb = 466
        ),
        ModelInfo(
            id = "translate-offline",
            title = "Oflayn tarjima modeli",
            description = "Internetsiz ingliz/rus → o'zbek tarjima. Keyingi bosqichda qo'shiladi.",
            url = null,
            approxSizeMb = 500,
            comingSoon = true
        ),
        ModelInfo(
            id = "tts-offline",
            title = "Oflayn ovoz sintezi (TTS)",
            description = "Internetsiz o'zbekcha ovoz. Keyingi bosqichda qo'shiladi.",
            url = null,
            approxSizeMb = 100,
            comingSoon = true
        )
    )
}

class ModelManager(context: Context) {
    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // katta fayllar uchun cheksiz o'qish vaqti
        .build()

    private val modelsDir: File by lazy {
        File(appContext.filesDir, "models").apply { mkdirs() }
    }

    fun fileFor(model: ModelInfo): File = File(modelsDir, "${model.id}.bin")

    fun isDownloaded(model: ModelInfo): Boolean {
        val f = fileFor(model)
        return f.exists() && f.length() > 0
    }

    suspend fun download(model: ModelInfo, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val url = model.url
                ?: return@withContext Result.failure(IllegalStateException("Bu model uchun hali URL yo'q"))
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Server xatosi: HTTP ${response.code}"))
                    }
                    val body = response.body
                        ?: return@withContext Result.failure(Exception("Bo'sh javob keldi"))

                    val totalBytes = body.contentLength()
                    val tempFile = File(modelsDir, "${model.id}.part")

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (totalBytes > 0) {
                                    onProgress(downloaded.toFloat() / totalBytes.toFloat())
                                }
                            }
                        }
                    }

                    if (!tempFile.renameTo(fileFor(model))) {
                        return@withContext Result.failure(Exception("Faylni saqlab bo'lmadi"))
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun delete(model: ModelInfo) {
        fileFor(model).delete()
    }
}
