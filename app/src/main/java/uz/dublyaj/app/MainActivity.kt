package uz.dublyaj.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import uz.dublyaj.app.ui.screens.DublyajScreen
import uz.dublyaj.app.ui.screens.KinoScreen
import uz.dublyaj.app.ui.screens.ModellarScreen
import uz.dublyaj.app.ui.screens.SozlamalarScreen
import uz.dublyaj.app.ui.theme.DublyajTheme
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DublyajTheme {
                AppRoot()
            }
        }
    }
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("kino", "Kino", Icons.Filled.Movie),
    TabItem("modellar", "Modellar", Icons.Filled.CloudDownload),
    TabItem("sozlamalar", "Sozlamalar", Icons.Filled.Settings)
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route

                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "kino",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("kino") {
                KinoScreen(onVideoSelected = { file ->
                    val encoded = URLEncoder.encode(file.absolutePath, "UTF-8")
                    navController.navigate("dublyaj/$encoded")
                })
            }
            composable("dublyaj/{videoPath}") { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("videoPath") ?: ""
                val path = URLDecoder.decode(encoded, "UTF-8")
                DublyajScreen(videoFile = File(path), onBack = { navController.popBackStack() })
            }
            composable("modellar") {
                ModellarScreen()
            }
            composable("sozlamalar") {
                SozlamalarScreen()
            }
        }
    }
}
