package com.apkforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apkforge.ui.edit.EditScreen
import com.apkforge.ui.editor.FileEditorScreen
import com.apkforge.ui.home.HomeScreen
import com.apkforge.ui.run.RunScreen
import com.apkforge.ui.theme.ApkForgeTheme
import com.apkforge.viewmodel.ForgeViewModel

private enum class Dest(val route: String, val label: String) {
    HOME("home", "Load"),
    RUN("run", "Run"),
    EDIT("edit", "Edit"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ApkForgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ApkForgeApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApkForgeApp(vm: ForgeViewModel = viewModel()) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    // Surface one-shot messages from the ViewModel as snackbars.
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { dest ->
                    val selected = currentRoute?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                when (dest) {
                                    Dest.HOME -> Icons.Filled.Upload
                                    Dest.RUN -> Icons.Filled.PlayArrow
                                    Dest.EDIT -> Icons.AutoMirrored.Filled.List
                                },
                                contentDescription = dest.label,
                            )
                        },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.HOME.route) {
                HomeScreen(vm, onLoaded = { navController.navigate(Dest.RUN.route) })
            }
            composable(Dest.RUN.route) { RunScreen(vm) }
            composable(Dest.EDIT.route) {
                EditScreen(vm, onOpenFile = { file, line ->
                    vm.openInEditor(file, line)
                    navController.navigate("editor")
                })
            }
            composable("editor") {
                FileEditorScreen(vm, onBack = { navController.popBackStack() })
            }
        }
    }
}
