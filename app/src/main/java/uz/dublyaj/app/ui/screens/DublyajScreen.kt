package uz.dublyaj.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.dublyaj.app.data.SettingsKeys
import uz.dublyaj.app.data.SettingsStore
import uz.dublyaj.app.data.pipeline.DubbingPipeline
import uz.dublyaj.app.data.pipeline.PipelineStep
import java.io.File

private val allSteps = PipelineStep.entries

@Composable
fun DublyajScreen(videoFile: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var currentStepIndex by remember { mutableStateOf(-1) }
    var running by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }

    fun startDubbing() {
        errorMessage = null
        resultFile = null
        currentStepIndex = -1
        running = true
        scope.launch {
            try {
                val groqKey = settingsStore.stringFlow(SettingsKeys.GROQ_API_KEY).first()
                val azureKey = settingsStore.stringFlow(SettingsKeys.AZURE_API_KEY).first()
                val azureRegion = settingsStore.stringFlow(SettingsKeys.AZURE_REGION).first()

                if (groqKey.isBlank() || azureKey.isBlank() || azureRegion.isBlank()) {
                    errorMessage = "Avval Sozlamalar bo'limida Groq va Azure kalitlarini kiriting."
                    running = false
                    return@launch
                }

                val pipeline = DubbingPipeline(context, groqKey, azureKey, azureRegion)
                val output = pipeline.run(videoFile) { step ->
                    currentStepIndex = step.index
                }
                resultFile = output
            } catch (e: Exception) {
                errorMessage = e.message ?: "Noma'lum xato yuz berdi"
            } finally {
                running = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Orqaga")
            }
            Text(videoFile.name, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Onlayn rejim: Groq (nutq tanish + tarjima) va Azure (ovoz) ishlatiladi. " +
                "Hozircha ovoz spikerga emas, navbat bo'yicha (erkak/ayol) biriktiriladi — " +
                "aniq spiker aniqlash keyingi bosqichda qo'shiladi.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(24.dp))

        allSteps.forEach { step ->
            val index = step.index
            val done = index < currentStepIndex || (index == currentStepIndex && !running)
            val active = index == currentStepIndex && running
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
                Text(step.label)
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Xato: $it", color = MaterialTheme.colorScheme.error)
        }

        resultFile?.let { file ->
            Spacer(modifier = Modifier.height(16.dp))
            Text("Tayyor! ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Videoni ulashish"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Videoni ulashish / saqlash")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { startDubbing() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "Ishlamoqda..." else "Dublyajni boshlash")
        }
    }
}
