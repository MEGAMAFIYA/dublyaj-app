package uz.dublyaj.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.dublyaj.app.BuildConfig
import uz.dublyaj.app.data.SettingsKeys
import uz.dublyaj.app.data.SettingsStore
import uz.dublyaj.app.data.network.UpdateChecker
import uz.dublyaj.app.data.network.UpdateInfo

@Composable
fun SozlamalarScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val updateChecker = remember { UpdateChecker(context) }
    val scope = rememberCoroutineScope()

    var groqKey by remember { mutableStateOf("") }
    var azureKey by remember { mutableStateOf("") }
    var azureRegion by remember { mutableStateOf("") }
    var hfToken by remember { mutableStateOf("") }
    var githubRepo by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var upToDateMessageShown by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) {
        groqKey = store.stringFlow(SettingsKeys.GROQ_API_KEY).first()
        azureKey = store.stringFlow(SettingsKeys.AZURE_API_KEY).first()
        azureRegion = store.stringFlow(SettingsKeys.AZURE_REGION).first()
        hfToken = store.stringFlow(SettingsKeys.HUGGINGFACE_TOKEN).first()
        githubRepo = store.stringFlow(SettingsKeys.GITHUB_REPO).first()
        loaded = true
    }

    fun checkForUpdate() {
        updateError = null
        updateInfo = null
        upToDateMessageShown = false
        if (githubRepo.isBlank()) {
            updateError = "Avval GitHub repo nomini kiriting (masalan: foydalanuvchi/dublyaj-app)."
            return
        }
        checkingUpdate = true
        scope.launch {
            try {
                val result = updateChecker.checkForUpdate(githubRepo)
                if (result == null) {
                    upToDateMessageShown = true
                } else {
                    updateInfo = result
                }
            } catch (e: Exception) {
                updateError = e.message ?: "Noma'lum xato"
            } finally {
                checkingUpdate = false
            }
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        updateError = null
        downloadProgress = 0f
        scope.launch {
            try {
                updateChecker.downloadAndInstall(info.apkUrl) { p -> downloadProgress = p }
            } catch (e: Exception) {
                updateError = e.message ?: "Yuklab olishda xato"
            } finally {
                downloadProgress = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Sozlamalar", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Bu kalitlar faqat ONLAYN rejimda (internet bo'lganda) yuqori sifatli " +
                "tarjima/ovoz uchun ishlatiladi. Ular faqat shu telefonda saqlanadi.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (loaded) {
            OutlinedTextField(
                value = groqKey,
                onValueChange = { groqKey = it; saved = false },
                label = { Text("Groq API kaliti") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = azureKey,
                onValueChange = { azureKey = it; saved = false },
                label = { Text("Azure Speech kaliti") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = azureRegion,
                onValueChange = { azureRegion = it; saved = false },
                label = { Text("Azure hudud (masalan: eastus)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = hfToken,
                onValueChange = { hfToken = it; saved = false },
                label = { Text("HuggingFace token (diarizatsiya uchun)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                scope.launch {
                    store.save(SettingsKeys.GROQ_API_KEY, groqKey)
                    store.save(SettingsKeys.AZURE_API_KEY, azureKey)
                    store.save(SettingsKeys.AZURE_REGION, azureRegion)
                    store.save(SettingsKeys.HUGGINGFACE_TOKEN, hfToken)
                    store.save(SettingsKeys.GITHUB_REPO, githubRepo)
                    saved = true
                }
            }) {
                Text("Saqlash")
            }

            if (saved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Saqlandi ✓", color = MaterialTheme.colorScheme.primary)
            }

            Divider(modifier = Modifier.padding(vertical = 24.dp))

            Text("Ilovani yangilash", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Hozirgi versiya: ${BuildConfig.VERSION_NAME} (kod: ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = githubRepo,
                onValueChange = { githubRepo = it; saved = false },
                label = { Text("GitHub repo (masalan: foydalanuvchi/dublyaj-app)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                downloadProgress != null -> {
                    LinearProgressIndicator(
                        progress = downloadProgress!!,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Yuklanmoqda: ${(downloadProgress!! * 100).toInt()}%")
                }
                updateInfo != null -> {
                    Text(
                        "Yangi versiya topildi (kod: ${updateInfo!!.remoteVersionCode})",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { downloadAndInstall(updateInfo!!) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Yuklab olish va o'rnatish")
                    }
                }
                else -> {
                    Button(
                        onClick = { checkForUpdate() },
                        enabled = !checkingUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (checkingUpdate) "Tekshirilmoqda..." else "Yangilanishni tekshirish")
                    }
                }
            }

            if (upToDateMessageShown) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Siz eng so'nggi versiyadasiz ✓", color = MaterialTheme.colorScheme.primary)
            }
            updateError?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Xato: $it", color = MaterialTheme.colorScheme.error)
            }
        } else {
            CircularProgressIndicator()
        }
    }
}
