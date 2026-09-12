package uz.dublyaj.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video tanlangandan so'ng, uni DARHOL ilovaning o'z ichki papkasiga
 * (cacheDir) nusxalab olamiz. Bu SAF (content://) ruxsatlari bilan bog'liq
 * "Permission Denial" xatolarini (ba'zi qurilmalarda — ayniqsa MIUI/Xiaomi —
 * takePersistableUriPermission() ishlamay qolishi mumkin) butunlay
 * chetlab o'tadi: nusxalangandan keyin bizga faqat oddiy, to'liq
 * o'zimizga tegishli fayl yo'li kerak bo'ladi, hech qanday tashqi
 * ruxsatga bog'liqlik qolmaydi.
 */
@Composable
fun KinoScreen(onVideoSelected: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        copying = true
        errorMessage = null
        scope.launch {
            try {
                val localFile = withContext(Dispatchers.IO) {
                    val destDir = File(context.cacheDir, "picked_video").apply { mkdirs() }
                    // Eski nusxalarni tozalab, joy va chalkashlikning oldini olamiz.
                    destDir.listFiles()?.forEach { it.delete() }
                    val dest = File(destDir, "video_${System.currentTimeMillis()}.mp4")
                    var copiedBytes = 0L
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            copiedBytes = input.copyTo(output, bufferSize = 1 * 1024 * 1024)
                        }
                    } ?: throw Exception("Videoni o'qib bo'lmadi (InputStream null)")

                    // Ba'zi qurilmalarda (masalan bulutga bog'langan galereya elementlari)
                    // nusxalash "xatosiz" tugaydi, lekin aslida to'liq bayt kelmaydi —
                    // bu holda MediaExtractor keyinroq tushunarsiz "Failed to instantiate
                    // extractor" xatosi bilan yiqiladi. Shu yerda darhol aniq tekshiramiz.
                    if (copiedBytes < 100_000L) {
                        throw Exception(
                            "Video to'liq nusxalanmadi (atigi $copiedBytes bayt keldi). " +
                                "Boshqa video bilan sinab ko'ring yoki avval videoni telefon " +
                                "xotirasiga (Fayllar ilovasi orqali) saqlab, shu yerdan tanlang."
                        )
                    }
                    dest
                }
                copying = false
                onVideoSelected(localFile)
            } catch (e: Exception) {
                copying = false
                errorMessage = "Videoni nusxalashda xato: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (copying) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Video tayyorlanmoqda...", style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(
                imageVector = Icons.Filled.VideoLibrary,
                contentDescription = null,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Dublyaj qilinadigan videoni tanlang",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { launcher.launch(arrayOf("video/*")) }) {
                Text("Galereyadan video tanlash")
            }
            errorMessage?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
