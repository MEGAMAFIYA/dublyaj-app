package uz.dublyaj.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun KinoScreen(onVideoSelected: (Uri) -> Unit) {
    val context = LocalContext.current

    // OpenDocument() (GetContent() emas) ishlatiladi, chunki u DOIMIY
    // (persistable) o'qish ruxsatini beradi. GetContent() faqat bir martalik,
    // darhol o'qish uchun mo'ljallangan — biz esa Uri'ni boshqa ekranda va
    // fon jarayonida keyinroq o'qiymiz, shu sabab "Permission Denial" xatosi
    // chiqqan edi.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onVideoSelected(it)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
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
    }
}
