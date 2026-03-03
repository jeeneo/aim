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

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.suspendCancellableCoroutine
import org.codeberg.dryerlint.aim.ui.BindDirDialog
import org.codeberg.dryerlint.aim.ui.ImageOptionsDialog
import org.codeberg.dryerlint.aim.ui.PartitionPickerDialog
import org.codeberg.dryerlint.aim.ui.PreferenceCategory
import org.codeberg.dryerlint.aim.ui.PreferenceItem
import org.codeberg.dryerlint.aim.ui.SwitchPreferenceItem
import org.codeberg.dryerlint.aim.ui.theme.AimTheme
import kotlin.coroutines.resume

// here we kinda copy the UI of MSD (https://github.com/chenxiaolong/MSD)
// in a kotliny way cause its simple enough and I find the UI to be pretty good
// cause this app is basically the reverse of MSD 
// hopefully mr gunnerson doesn't mind

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        val coordinator = CoordinatorLayout(this)
        val composeView = ComposeView(this).apply {
            setContent { AimTheme { AimApp() } }
        }
        coordinator.addView(
            composeView, CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        setContentView(coordinator)
    }
}

@SuppressLint("SdCardPath")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AimApp(viewModel: MainActivityViewModel = viewModel()) {
    val versionName = remember {
        val version = BuildConfig.VERSION_NAME.removeSuffix(".debug")
        "$version (${BuildConfig.BUILD_TYPE})"
    }
    val snackbarAnchor = LocalView.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val canAct by viewModel.canAct.collectAsState()
    val envStatus by viewModel.envStatus.collectAsState()
    val envChecked by viewModel.envChecked.collectAsState()
    val images by viewModel.images.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val partitionState by viewModel.partitionPicker.collectAsState()
    val bindDir by viewModel.bindDir.collectAsState()
    val pkgName = LocalContext.current.packageName
    var dialogImagePath by remember { mutableStateOf<String?>(null) }
    var showBindDirEdit by remember { mutableStateOf(false) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { viewModel.addImage(it) }
        }

    LaunchedEffect(alerts) {
        // always show the most-recent alert (new notifications replace the old)
        val alert = alerts.lastOrNull() ?: return@LaunchedEffect
        suspendCancellableCoroutine { cont ->
            val snackbar = Snackbar.make(snackbarAnchor, alert.message, Snackbar.LENGTH_LONG)
            snackbar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation { snackbar.dismiss() }
            snackbar.show()
        }
        viewModel.acknowledgeFirstAlert()
    }

    val showEnv = envChecked && !envStatus.ready

    if (showBindDirEdit) {
        BindDirDialog(
            currentDir = bindDir,
            isGlobal = true,
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
    var showImageBindDirEdit by remember { mutableStateOf<String?>(null) }
    if (dialogImage != null) {
        ImageOptionsDialog(
            title = dialogImage.displayName,
            onDismiss = { dialogImagePath = null },
            safExposed = dialogImage.exposeInSAF,
            onSafChange = { expose -> viewModel.toggleImageSafExpose(dialogImage.path, expose) },
            storageExposed = dialogImage.exposeInStorage,
            onStorageChange = { expose ->
                viewModel.toggleImageStorageExpose(
                    dialogImage.path, expose
                )
            },
            onRemove = {
                dialogImagePath = null
                viewModel.removeImage(dialogImage.path)
            },
            onFormat = { fsType ->
                viewModel.formatImage(dialogImage.path, fsType)
            },
            showFormat = !dialogImage.isReadOnly,
            onChangePartition = {
                viewModel.changePartition(dialogImage.path)
            },
            isMultipart = dialogImage.hasPartitions,
            bindDir = dialogImage.bindDir,
            onBindDirChange = {
                showImageBindDirEdit = dialogImage.path
            },
            onBindDirReset = {
                viewModel.setImageBindDir(dialogImage.path, null)
            },
        )
    }

    if (showImageBindDirEdit != null) {
        val imageForDialog = images.firstOrNull { it.path == showImageBindDirEdit }
        BindDirDialog(
            currentDir = imageForDialog?.bindDir ?: bindDir,
            isGlobal = false,
            onDismiss = { showImageBindDirEdit = null },
            onConfirm = { newDir ->
                val validatedDir = newDir.trim().trimEnd('/').takeIf { it.isNotEmpty() }
                if (validatedDir != imageForDialog?.bindDir) {
                    viewModel.setImageBindDir(showImageBindDirEdit!!, validatedDir)
                }
                showImageBindDirEdit = null
            },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { }) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
                                summaryColor = if (envStatus.rootAvailable && envStatus.busyboxAvailable) MaterialTheme.colorScheme.onSurfaceVariant
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                        )
                        PreferenceItem(
                            title = stringResource(R.string.pref_add_image_name),
                            summary = stringResource(R.string.pref_add_image_desc),
                            enabled = canAct,
                            onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) })
                    }
                }
                itemsIndexed(items = images, key = { _, img -> "img_${img.path}" }) { _, img ->
                    Spacer(modifier = Modifier.height(4.dp))
                    SwitchPreferenceItem(
                        title = img.displayName,
                        summary = img.path,
                        checked = img.enabled,
                        enabled = canAct && envStatus.ready,
                        onCheckedChange = { enabled -> viewModel.toggleImage(img.path, enabled) },
                        onClick = { dialogImagePath = img.path })
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
                                val stem = img.mountedImage?.mountPoint?.substringAfterLast('/')
                                    ?: img.displayName
                                val imageBindDir = img.bindDir ?: bindDir
                                val isCustomBind = img.bindDir != null
                                val paths = buildList {
                                    if (img.isExposed) add("content://aim/$stem")
                                    if (img.isStorageExposed) {
                                        add(if (isCustomBind) imageBindDir else "$imageBindDir/$stem")
                                    }
                                    if (isEmpty()) add("/data/data/$pkgName/mounts/$stem")
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
                        onClick = { viewModel.applySettings() })
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        HorizontalDivider()
                    }
                }
                item(key = "cat_settings") {
                    PreferenceCategory(title = stringResource(R.string.pref_header_settings)) {
                        PreferenceItem(
                            title = stringResource(R.string.pref_bindmount_dir_name),
                            summary = bindDir,
                            enabled = canAct,
                            onClick = { showBindDirEdit = true },
                            onLongClick = { viewModel.setBindDir("/data/media/0/mounts") })
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        HorizontalDivider()
                    }
                }
                item(key = "cat_about") {
                    val debugMode by viewModel.debugMode.collectAsState()
                    PreferenceCategory(title = stringResource(R.string.pref_header_about)) {
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        PreferenceItem(
                            title = stringResource(R.string.pref_version_name),
                            summary = versionName,
                            onClick = { uriHandler.openUri("https://codeberg.org/dryerlint/AIM") },
                            onLongClick = { viewModel.toggleDebugMode() })
                        if (debugMode) {
                            SwitchPreferenceItem(
                                title = stringResource(R.string.pref_debug_mode_name),
                                summary = stringResource(R.string.pref_debug_mode_desc_on),
                                checked = true,
                                enabled = canAct,
                                onCheckedChange = { viewModel.toggleDebugMode() },
                            )
                        }
                    }
                }
            }
            if (!canAct) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
