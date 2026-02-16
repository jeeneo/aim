/**
* Copyright (C) 2026 dryerlint <codeberg.org/dryerlint>
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:Suppress("SpellCheckingInspection", "AssignedValueIsNeverRead")
package org.codeberg.dryerlint.aim

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.codeberg.dryerlint.aim.ui.ImageOptionsDialog
import org.codeberg.dryerlint.aim.ui.PartitionPickerDialog
import org.codeberg.dryerlint.aim.ui.PreferenceCategory
import org.codeberg.dryerlint.aim.ui.PreferenceItem
import org.codeberg.dryerlint.aim.ui.SwitchPreferenceItem
import org.codeberg.dryerlint.aim.ui.BindDirDialog
import org.codeberg.dryerlint.aim.ui.theme.AimTheme

// here we kinda copy the UI of MSD (https://github.com/chenxiaolong/MSD)
// in a kotliny way cause its simple enough and i find the UI to be pretty good
// cause this app is basically the reverse of MSD 
// hopefully mr gunnerson doesnt mind

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        setContent { AimTheme { AimApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AimApp(viewModel: AimViewModel = viewModel()) {
    val versionName = remember {
        val version = BuildConfig.VERSION_NAME.removeSuffix(".debug")
        "$version (${BuildConfig.BUILD_TYPE})"
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val canAct by viewModel.canAct.collectAsState()
    val envStatus by viewModel.envStatus.collectAsState()
    val envChecked by viewModel.envChecked.collectAsState()
    val images by viewModel.images.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val partitionState by viewModel.partitionPicker.collectAsState()
    val bindDir by viewModel.bindDir.collectAsState()
    var dialogImagePath by remember { mutableStateOf<String?>(null) }
    var showBindDirEdit by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.addImage(it) }
    }

    LaunchedEffect(alerts) {
        val alert = alerts.firstOrNull() ?: return@LaunchedEffect
        val message = alert.message
        val result = snackbarHostState.showSnackbar(message)
        if (result == SnackbarResult.Dismissed || result == SnackbarResult.ActionPerformed) {
            viewModel.acknowledgeFirstAlert()
        }
    }

    val showEnv = envChecked && !envStatus.ready

    if (showBindDirEdit) {
        BindDirDialog(
            currentDir = bindDir,
            onDismiss = { showBindDirEdit = false },
            onConfirm = { newDir ->
                viewModel.setBindDir(newDir)
                showBindDirEdit = false
            },
        )
    }

    val ppState = partitionState
    if (ppState != null) {
        PartitionPickerDialog(
            title = ppState.displayName,
            partitions = ppState.partitions,
            totalSizeBytes = ppState.totalSizeBytes,
            scheme = ppState.scheme,
            initialSelectedIndex = ppState.selectedPartitionIndex,
            onDismiss = { viewModel.dismissPartitionPicker() },
            onSelect = { partition -> viewModel.selectPartition(partition) },
        )
    }

    val dialogImage = images.firstOrNull { it.path == dialogImagePath }
    if (dialogImage != null) {
        ImageOptionsDialog(
            title = dialogImage.displayName,
            onDismiss = { dialogImagePath = null },
            safExposed = dialogImage.exposeInSAF,
            onSafChange = { expose -> viewModel.toggleImageSafExpose(dialogImage.path, expose) },
            storageExposed = dialogImage.exposeInStorage,
            onStorageChange = { expose -> viewModel.toggleImageStorageExpose(dialogImage.path, expose) },
            onRemove = {
                dialogImagePath = null
                viewModel.removeImage(dialogImage.path)
            },
            onFormat = {
                viewModel.formatImage(dialogImage.path)
            },
            showFormat = !dialogImage.isReadOnly,
            onChangePartition = {
                viewModel.changePartition(dialogImage.path)
            },
            showChangePartition = dialogImage.hasPartitions,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar( title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (showEnv) {
                    item(key = "cat_env") {
                        PreferenceCategory(title = stringResource(R.string.pref_header_environment)) {
                            PreferenceItem(
                                title = stringResource(R.string.pref_root_busybox_status_name),
                                summary = buildString {
                                    append(envStatus.rootMessage)
                                    append("\n")
                                    append(envStatus.busyboxMessage)
                                    if (envStatus.busyboxPath.isNotBlank()) append(" (${envStatus.busyboxPath})")
                                },
                                enabled = canAct,
                                summaryColor = if (envStatus.rootAvailable && envStatus.busyboxAvailable) 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                            PreferenceItem(
                                title = stringResource(R.string.pref_retry_checks_name),
                                summary = stringResource(R.string.pref_retry_checks_desc),
                                enabled = canAct,
                                onClick = { viewModel.checkEnvironment() },
                            )
                        }
                    }
                }
                item(key = "cat_images_header") {
                    PreferenceCategory(title = stringResource(R.string.pref_header_images)) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                        PreferenceItem(
                            title = stringResource(R.string.pref_add_image_name),
                            summary = stringResource(R.string.pref_add_image_desc),
                            enabled = canAct,
                            onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }
                        )
                    }
                }
                itemsIndexed(items = images, key = { _, img -> "img_${img.path}" })
                { _, img ->
                    Spacer(modifier = Modifier.height(4.dp))
                    SwitchPreferenceItem(
                        title = img.displayName,
                        summary = img.path,
                        checked = img.enabled,
                        enabled = canAct && envStatus.ready,
                        onCheckedChange = { enabled -> viewModel.toggleImage(img.path, enabled) },
                        onClick = { dialogImagePath = img.path }
                    )
                }
                val mountedImages = images.filter { it.isMounted }
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                }
                if (images.isEmpty()) {
                    item(key = "cat_no_images") {
                        PreferenceCategory(title = stringResource(R.string.pref_header_images)) {
                            PreferenceItem(
                                title = stringResource(R.string.pref_no_images_name),
                                summary = stringResource(R.string.pref_no_images_desc),
                                enabled = false
                            )
                        }
                    }
                }
                item(key = "cat_active_mounts") {
                    PreferenceCategory(title = stringResource(R.string.pref_header_active_mounts)) {
                        if (mountedImages.isNotEmpty()) {
                            val mountsSummary = mountedImages.joinToString("\n") { img ->
                                val stem = img.mountedImage?.mountPoint?.substringAfterLast('/') ?: img.displayName
                                val paths = buildList {
                                    if (img.isExposed) add("content://aim/$stem")
                                    if (img.isStorageExposed) add(when {
                                        bindDir.startsWith("/mnt/media_rw") -> "/mnt/media_rw/$stem"
                                        else -> "$bindDir/$stem"
                                    })
                                    if (isEmpty()) add("internal/mounts/$stem")
                                }
                                "${img.displayName} (${paths.joinToString(", ")})"
                            }
                            PreferenceItem(
                                title = stringResource(R.string.mounted_images_title),
                                summary = mountsSummary
                            )
                        } else {
                            PreferenceItem(
                                title = stringResource(R.string.pref_no_mounts_name),
                                summary = stringResource(R.string.pref_no_mounts_desc),
                                enabled = false
                            )
                        }
                    }
                }
                item(key = "apply_settings") {
                    PreferenceItem(
                        title = stringResource(R.string.pref_apply_settings_name),
                        summary = stringResource(R.string.pref_apply_settings_desc),
                        enabled = canAct && envStatus.ready,
                        onClick = { viewModel.applySettings() }
                    )
                }
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        HorizontalDivider()
                    }
                }
                item(key = "cat_settings") {
                    PreferenceCategory(title = stringResource(R.string.pref_header_settings)) {
                        PreferenceItem(
                            title = stringResource(R.string.pref_bindmount_dir_name),
                            summary = bindDir,
                            enabled = canAct,
                            onClick = { showBindDirEdit = true }
                        )
                    }
                }
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        HorizontalDivider()
                    }
                }
                item(key = "cat_about") {
                    PreferenceCategory(title = stringResource(R.string.pref_header_about)) {
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        PreferenceItem(
                            title = stringResource(R.string.pref_version_name),
                            summary = versionName,
                            onClick = { uriHandler.openUri("https://codeberg.org/dryerlint/AIM") }
                        )
                    }
                }
            }
            if (!canAct) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
