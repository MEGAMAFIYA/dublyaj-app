package uz.dublyaj.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val steps = listOf(
    "1/7 Video yuklandi",
    "2/7 Audio ajratilmoqda",
    "3/7 Nutq matnga aylantirilmoqda",
    "4/7 Tarjima qilinmoqda",
    "5/7 Subtitr yaratilmoqda",
    "6/7 Dublyaj (ovoz) yaratilmoqda",
    "7/7 Video va audio birlashtirilmoqda"
)

@Composable
fun DublyajScreen(videoUri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    var fileName by remember { mutableStateOf("Video") }
    var currentStep by remember { mutableStateOf(-1) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(videoUri) {
        runCatching {
            context.contentResolver.query(videoUri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(idx) ?: "Video"
                }
            }
        }
    }

    LaunchedEffect(running) {
        if (running) {
            for (i in steps.indices) {
                currentStep = i
                delay(900)
            }
            running = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Orqaga")
            }
            Text(fileName, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Diqqat: bu — interfeys namoyishi (demo). Haqiqiy nutq tanish / tarjima / " +
                "dublyaj mantiqi keyingi bosqichda shu ekranga ulanadi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(24.dp))

        steps.forEachIndexed { index, label ->
            val done = index < currentStep || (index == currentStep && !running)
            val active = index == currentStep && running
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    done -> Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    active -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    else -> Spacer(modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(label)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { running = true; currentStep = -1 },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "Ishlamoqda..." else "Simulyatsiyani boshlash (demo)")
        }
    }
}
