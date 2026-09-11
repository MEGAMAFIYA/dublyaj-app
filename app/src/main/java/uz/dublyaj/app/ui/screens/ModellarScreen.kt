package uz.dublyaj.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uz.dublyaj.app.data.ModelCatalog
import uz.dublyaj.app.data.ModelInfo
import uz.dublyaj.app.data.ModelManager

@Composable
fun ModellarScreen() {
    val context = LocalContext.current
    val manager = remember { ModelManager(context) }
    val scope = rememberCoroutineScope()

    val progressMap = remember { mutableStateMapOf<String, Float>() }
    val errorMap = remember { mutableStateMapOf<String, String>() }
    var refreshTrigger by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Kerakli modellar", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Modelni bir marta yuklab olsangiz, telefonda saqlanib qoladi va ilova " +
                "internetsiz ham ishlay oladi. Qayta yuklab olishning hojati yo'q.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ModelCatalog.models) { model ->
                key(model.id, refreshTrigger) {
                    ModelRow(
                        model = model,
                        downloaded = manager.isDownloaded(model),
                        progress = progressMap[model.id],
                        error = errorMap[model.id],
                        onDownload = {
                            errorMap.remove(model.id)
                            progressMap[model.id] = 0f
                            scope.launch {
                                val result = manager.download(model) { p ->
                                    progressMap[model.id] = p
                                }
                                progressMap.remove(model.id)
                                result.onFailure { e ->
                                    errorMap[model.id] = e.message ?: "Noma'lum xato"
                                }
                                refreshTrigger++
                            }
                        },
                        onDelete = {
                            manager.delete(model)
                            refreshTrigger++
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    downloaded: Boolean,
    progress: Float?,
    error: String?,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleMedium)
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("~${model.approxSizeMb} MB", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(8.dp))

            when {
                model.comingSoon -> {
                    AssistChip(onClick = {}, enabled = false, label = { Text("Tez orada") })
                }
                progress != null -> {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                downloaded -> {
                    Row {
                        AssistChip(onClick = {}, enabled = false, label = { Text("Yuklab olingan ✓") })
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDelete) { Text("O'chirish") }
                    }
                }
                else -> {
                    Button(onClick = onDownload) { Text("Yuklab olish") }
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Xato: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
