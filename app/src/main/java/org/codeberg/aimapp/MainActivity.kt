// SPDX-License-Identifier: GPL-3.0-or-later

@file:Suppress("SpellCheckingInspection", "AssignedValueIsNeverRead")

package org.codeberg.aimapp

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.codeberg.aimapp.AimApplication.Companion.ctx
import org.codeberg.aimapp.ui.BindDirDialog
import org.codeberg.aimapp.ui.CardPosition
import org.codeberg.aimapp.ui.GroupedListSpacing
import org.codeberg.aimapp.ui.GroupedRow
import org.codeberg.aimapp.ui.HapticPatterns
import org.codeberg.aimapp.ui.ImageOptionsDialog
import org.codeberg.aimapp.ui.PartitionPickerDialog
import org.codeberg.aimapp.ui.SectionHeader
import org.codeberg.aimapp.ui.SnackbarHost
import org.codeberg.aimapp.ui.positionFor
import org.codeberg.aimapp.ui.screenContentPadding
import org.codeberg.aimapp.ui.theme.AimTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ctx = applicationContext
        installSplashScreen()
        enableEdgeToEdge()
        setContent { AimTheme { AimApp() } }
    }
}

@SuppressLint("SdCardPath", "MissingHapticFeedback")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AimApp(viewModel: MainActivityViewModel = viewModel()) {
    val versionName = remember {
        val version = BuildConfig.VERSION_NAME.removeSuffix(".debug")
        "$version (${BuildConfig.BUILD_TYPE})"
    }
    val isBusy by viewModel.isBusy.collectAsState()
    val envStatus by viewModel.envStatus.collectAsState()
    val envChecked by viewModel.envChecked.collectAsState()
    val images by viewModel.images.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val partitionState by viewModel.partitionPicker.collectAsState()
    val bindDir by viewModel.bindDir.collectAsState()
    val showSettingsConfirm by viewModel.showSettingsConfirm.collectAsState()
    val pkgName = LocalContext.current.packageName
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialogImagePath by remember { mutableStateOf<String?>(null) }
    var showBindDirEdit by remember { mutableStateOf(false) }

    LaunchedEffect(alerts) {
        alerts.firstOrNull()?.let { alert ->
            snackbarHostState.currentSnackbarData?.dismiss()
            scope.launch {
                snackbarHostState.showSnackbar(alert.message)
                viewModel.acknowledgeFirstAlert()
            }
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { viewModel.addImage(it) }
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

    if (showSettingsConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSettingsDialog() },
            title = { Text(text = stringResource(R.string.posix_warning_header)) },
            text = { Text(text = stringResource(R.string.posix_warning_body)) },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSettingsDialog() }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmSettingsDialog() }) {
                    Text(text = stringResource(R.string.ok))
                }
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
            isMountFlow = ppState.isMountFlow,
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

    val sheetShowing =
        dialogImagePath != null || showBindDirEdit || showImageBindDirEdit != null || ppState != null

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .screenContentPadding(padding),
            ) {
                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(GroupedListSpacing),
                ) {
                    if (showEnv) {
                        item(key = "cat_env_header") {
                            SectionHeader(text = stringResource(R.string.pref_header_environment))
                            GroupedRow(
                                position = positionFor(1, 2)
                            ) {
                                GroupedTextContent(
                                    title = stringResource(R.string.pref_root_busybox_status_name),
                                    summary = buildString {
                                        append(envStatus.rootMessage)
                                        append("\n")
                                        append(envStatus.busyboxMessage)
                                        if (envStatus.busyboxPath.isNotBlank()) append(" (${envStatus.busyboxPath})")
                                    },
                                    summaryColor = if (envStatus.rootAvailable && envStatus.busyboxAvailable) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                        item(key = "cat_env_body") {
                            GroupedRow(
                                position = positionFor(2, 2),
                                onClick = if (!isBusy) ({ viewModel.checkEnvironment() }) else null,
                            ) {
                                GroupedTextContent(
                                    title = stringResource(R.string.pref_retry_checks_name),
                                    summary = stringResource(R.string.pref_retry_checks_desc),
                                )
                            }
                        }
                    }
                    val totalRows = images.size + 1
                    item(key = "cat_images") {
                        SectionHeader(text = stringResource(R.string.pref_header_images))
                        GroupedRow(
                            position = positionFor(
                                1, totalRows
                            ),
                            enabled = !isBusy,
                            onClick = if (!isBusy) {
                                { picker.launch(arrayOf("application/octet-stream", "*/*")) }
                            } else null,
                        ) {
                            GroupedTextContent(
                                title = stringResource(R.string.pref_add_image_name),
                                summary = stringResource(R.string.pref_add_image_desc),
                            )
                        }
                    }
                    itemsIndexed(
                        items = images, key = { _, img -> "img_${img.path}" }) { index, img ->
                        GroupedRow(
                            position = positionFor(
                                index + 2, totalRows
                            ),
                            enabled = !isBusy,
                            onClick = { dialogImagePath = img.path },
                        ) {
                            GroupedTextContent(
                                title = img.displayName,
                                summary = img.path,
                                modifier = Modifier.weight(1f),
                            )
                            VerticalDivider(
                                modifier = Modifier
                                    .height(38.dp)
                                    .padding(horizontal = 12.dp)
                                    .clip(RoundedCornerShape(50)),
                                thickness = 3.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Switch(
                                checked = img.enabled,
                                enabled = !isBusy && envStatus.ready,
                                thumbContent = {
                                    Icon(
                                        imageVector = if (img.enabled) Icons.Filled.Check else Icons.Filled.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                                onCheckedChange = { enabled ->
                                    HapticPatterns.tap()
                                    viewModel.toggleImage(img.path, enabled)
                                },
                                modifier = Modifier.padding(end = 2.dp),
                            )
                        }
                    }
                    val mountedImages = images.filter { it.isMounted }
                    item(key = "cat_active_mounts") {
                        SectionHeader(text = stringResource(R.string.pref_header_active_mounts))
                    }
                    if (mountedImages.isEmpty()) {
                        item(key = "no_active_mounts") {
                            GroupedRow(
                                position = positionFor(1, 2),
                                enabled = false,
                            ) {
                                GroupedTextContent(
                                    title = stringResource(R.string.main_no_mounts), summary = null
                                )
                            }
                        }
                    } else {
                        items(
                            mountedImages.size,
                            key = { index -> mountedImages[index].path }) { index ->
                            val img = mountedImages[index]
                            val totalCount = mountedImages.size + 1
                            GroupedRow(
                                position = positionFor(
                                    index + 1, totalCount
                                ),
                                enabled = !isBusy,
                                onClick = { dialogImagePath = img.path },
                            ) {
                                val stem = img.mountedImage?.mountPoint?.substringAfterLast('/')
                                    ?: img.displayName
                                val imageBindDir = img.bindDir ?: bindDir
                                val paths = buildList {
                                    if (img.isExposed) add("content://aim/$stem")
                                    if (img.isStorageExposed) {
                                        add(
                                            if (img.bindDir != null) {
                                                imageBindDir
                                            } else {
                                                "$imageBindDir/$stem"
                                            }
                                        )
                                    }
                                    if (isEmpty()) {
                                        add("/data/data/$pkgName/mounts/$stem")
                                    }
                                }
                                GroupedTextContent(
                                    title = img.displayName, summary = paths.joinToString(", ")
                                )
                            }
                        }
                    }
                    item(key = "apply_settings") {
                        GroupedRow(
                            position = positionFor(2, 2),
                            enabled = !isBusy && envStatus.ready && images.isNotEmpty(),
                            onClick = { viewModel.applySettings() }) {
                            Column {
                                Text(
                                    text = stringResource(R.string.main_apply_mounts),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.pref_apply_settings_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item(key = "cat_settings") {
                        SectionHeader(text = stringResource(R.string.pref_header_settings))
                        GroupedRow(
                            position = CardPosition.Solo,
                            onClick = if (!isBusy) ({ showBindDirEdit = true }) else null,
                            onLongClick = if (!isBusy) {
                                { viewModel.setBindDir("/storage/emulated/0/mounts") }
                            } else null,
                        ) {
                            GroupedTextContent(
                                title = stringResource(R.string.pref_bindmount_dir_name),
                                summary = bindDir,
                            )
                        }
                    }
                    item(key = "cat_about") {
                        SectionHeader(text = stringResource(R.string.pref_header_about))
                        val uriHandler = LocalUriHandler.current
                        GroupedRow(
                            position = CardPosition.Solo,
                            onClick = { uriHandler.openUri("https://github.com/jeeneo/aim") },
                        ) {
                            GroupedTextContent(
                                title = stringResource(R.string.main_version_name),
                                summary = versionName,
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(if (sheetShowing) Alignment.TopCenter else Alignment.BottomCenter)
                .then(
                    if (sheetShowing) Modifier.statusBarsPadding().padding(top = 8.dp)
                    else Modifier.navigationBarsPadding().padding(bottom = 8.dp)
                )
        ) {
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}

@Composable
private fun GroupedTextContent(
    modifier: Modifier = Modifier,
    title: String,
    summary: String? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = summaryColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
