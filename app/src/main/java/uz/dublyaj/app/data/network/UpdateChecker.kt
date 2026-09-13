package uz.dublyaj.app.data.network

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uz.dublyaj.app.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val remoteVersionCode: Int, val apkUrl: String)

/**
 * Ilovani o'zini yangilash uchun. GitHub API (api.github.com) ISHLATILMAYDI —
 * uning o'rniga GitHub'ning doimiy "latest release" fayl havolalaridan
 * foydalaniladi:
 *   https://github.com/{repo}/releases/latest/download/{fayl_nomi}
 * Bu oddiy HTTP fayl havolasi (avtomatik eng so'nggi release'ga yo'naltiradi),
 * shuning uchun API so'rov chegarasi (rate limit) yoki token talab qilinmaydi.
 *
 * .github/workflows/build.yml har bir build'da versionCode'ni "version.txt"
 * sifatida ham release'ga qo'shib qo'yadi — shu fayl orqali "yangi versiya
 * bormi" tekshiriladi, to'liq APK'ni yuklab olishdan oldin.
 */
class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** @return yangilanish topilsa UpdateInfo, aks holda null (hozirgi versiya eng so'nggisi). */
    suspend fun checkForUpdate(repo: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val cleanRepo = repo.trim().trim('/')
        if (cleanRepo.isBlank() || !cleanRepo.contains("/")) {
            throw Exception("Repo noto'g'ri formatda. Masalan: foydalanuvchi/dublyaj-app")
        }

        val versionUrl = "https://github.com/$cleanRepo/releases/latest/download/version.txt"
        val request = Request.Builder().url(versionUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(
                    "Yangilanish ma'lumotini topib bo'lmadi (HTTP ${response.code}). " +
                        "Repo nomi to'g'riligini va kamida bitta muvaffaqiyatli build " +
                        "(Release) borligini tekshiring."
                )
            }
            val body = response.body?.string()?.trim().orEmpty()
            val remoteVersionCode = body.toIntOrNull()
                ?: throw Exception("Versiya ma'lumoti noto'g'ri formatda qaytdi: '${body.take(50)}'")

            if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
                return@withContext null
            }

            val apkUrl = "https://github.com/$cleanRepo/releases/latest/download/app-debug.apk"
            UpdateInfo(remoteVersionCode, apkUrl)
        }
    }

    /** APK'ni yuklab oladi va tizim o'rnatish oynasini ochadi (chinakam "jimgina" o'rnatish emas — Android buni oddiy ilovaga ruxsat bermaydi). */
    suspend fun downloadAndInstall(apkUrl: String, onProgress: (Float) -> Unit) {
        val destDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val destFile = File(destDir, "update.apk")

        withContext(Dispatchers.IO) {
            destDir.listFiles()?.forEach { it.delete() }
            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("APK yuklab olinmadi (HTTP ${response.code})")
                }
                val body = response.body ?: throw Exception("Bo'sh javob keldi")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                        }
                    }
                }
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
