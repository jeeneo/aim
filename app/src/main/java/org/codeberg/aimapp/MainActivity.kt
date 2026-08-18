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

package org.codeberg.aimapp

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.suspendCancellableCoroutine
import org.codeberg.aimapp.ui.BindDirDialog
import org.codeberg.aimapp.ui.CardPosition
import org.codeberg.aimapp.ui.GroupedListSpacing
import org.codeberg.aimapp.ui.GroupedRow
import org.codeberg.aimapp.ui.ImageOptionsDialog
import org.codeberg.aimapp.ui.PartitionPickerDialog
import org.codeberg.aimapp.ui.ScreenScaffold
import org.codeberg.aimapp.ui.buttonShape
import org.codeberg.aimapp.ui.positionFor
import org.codeberg.aimapp.ui.screenContentPadding
import org.codeberg.aimapp.ui.theme.AimTheme
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

@SuppressLint("SdCardPath", "MissingHapticFeedback")
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
    val ksuProfileError = stringResource(R.string.error_ksu_or_alike_permission)
    var dialogImagePath by remember { mutableStateOf<String?>(null) }
    var showBindDirEdit by remember { mutableStateOf(false) }
    var showKsuProfileDialog by remember { mutableStateOf(false) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { viewModel.addImage(it) }
        }

    LaunchedEffect(alerts) {
        val alert = alerts.lastOrNull() ?: return@LaunchedEffect
        if (!showKsuProfileDialog && alert.message.contains(ksuProfileError)) {
            showKsuProfileDialog = true
            return@LaunchedEffect
        }
        if (showKsuProfileDialog) return@LaunchedEffect
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

    if (showKsuProfileDialog) {
        AlertDialog(
            onDismissRequest = {
                showKsuProfileDialog = false
                viewModel.acknowledgeFirstAlert()
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKsuProfileDialog = false
                        viewModel.acknowledgeFirstAlert()
                    }) {
                    Text(text = stringResource(R.string.dialog_ok))
                }
            },
            text = { Text(text = ksuProfileError) },
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

    ScreenScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scrollBehavior
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(padding)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (showEnv) {
                    item(key = "cat_env") {
                        GroupedSectionHeader(text = stringResource(R.string.pref_header_environment))
                        GroupedRow(
                            position = positionFor(1, 2),
                            modifier = Modifier.alpha(if (canAct) 1f else 0.38f),
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
                        GroupedRow(
                            position = positionFor(2, 2),
                            modifier = Modifier.alpha(if (canAct) 1f else 0.38f),
                            onClick = if (canAct) ({ viewModel.checkEnvironment() }) else null,
                        ) {
                            GroupedTextContent(
                                title = stringResource(R.string.pref_retry_checks_name),
                                summary = stringResource(R.string.pref_retry_checks_desc),
                            )
                        }
                    }
                }
                item(key = "cat_images") {
                    GroupedSectionHeader(text = stringResource(R.string.pref_header_images))
                    GroupedRow(
                        position = CardPosition.Solo,
                        modifier = Modifier.alpha(if (canAct) 1f else 0.38f),
                        onClick = if (canAct) {
                            { picker.launch(arrayOf("application/octet-stream", "*/*")) }
                        } else null,
                    ) {
                        GroupedTextContent(
                            title = stringResource(R.string.pref_add_image_name),
                            summary = stringResource(R.string.pref_add_image_desc),
                        )
                    }
                    if (images.isEmpty()) {
                        Spacer(modifier = Modifier.height(GroupedListSpacing))
                        GroupedRow(
                            position = CardPosition.Solo,
                            modifier = Modifier.alpha(0.38f),
                        ) {
                            GroupedTextContent(
                                title = stringResource(R.string.pref_no_images_name),
                                summary = stringResource(R.string.pref_no_images_desc),
                            )
                        }
                    }
                }
                itemsIndexed(items = images, key = { _, img -> "img_${img.path}" }) { _, img ->
                    Spacer(modifier = Modifier.height(GroupedListSpacing))
                    GroupedRow(
                        position = CardPosition.Solo,
                        modifier = Modifier.alpha(if (canAct && envStatus.ready) 1f else 0.38f),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (canAct && envStatus.ready) {
                                        Modifier.combinedClickable(onClick = { dialogImagePath = img.path })
                                    } else Modifier
                                )
                                .padding(end = 8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.Center) {
                                Text(
                                    text = img.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = img.path,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .width(1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        AimSwitch(
                            checked = img.enabled,
                            enabled = canAct && envStatus.ready,
                            onCheckedChange = { enabled -> viewModel.toggleImage(img.path, enabled) },
                        )
                    }
                }
                val mountedImages = images.filter { it.isMounted }
                item(key = "cat_active_mounts") {
                    GroupedSectionHeader(text = stringResource(R.string.pref_header_active_mounts))
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
                        GroupedRow(position = CardPosition.Solo) {
                            GroupedTextContent(
                                title = stringResource(R.string.mounted_images_title),
                                summary = mountsSummary,
                            )
                        }
                    } else {
                        GroupedRow(
                            position = CardPosition.Solo,
                            modifier = Modifier.alpha(0.38f),
                        ) {
                            GroupedTextContent(
                                title = stringResource(R.string.pref_no_mounts_name),
                                summary = stringResource(R.string.pref_no_mounts_desc),
                            )
                        }
                    }
                }
                item(key = "apply_settings") {
                    Button(
                        onClick = { viewModel.applySettings() },
                        enabled = canAct && envStatus.ready,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = GroupedListSpacing),
                        shape = buttonShape(position = CardPosition.Solo, isShown = false),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.pref_apply_settings_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                item(key = "cat_settings") {
                    GroupedSectionHeader(text = stringResource(R.string.pref_header_settings))
                    GroupedRow(
                        position = CardPosition.Solo,
                        modifier = Modifier.alpha(if (canAct) 1f else 0.38f),
                        onClick = if (canAct) ({ showBindDirEdit = true }) else null,
                        onLongClick = if (canAct) {
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
                    GroupedSectionHeader(text = stringResource(R.string.pref_header_about))
                    val uriHandler = LocalUriHandler.current
                    GroupedRow(
                        position = CardPosition.Solo,
                        onClick = { uriHandler.openUri("https://github.com/jeeneo/aim") },
                    ) {
                        GroupedTextContent(
                            title = stringResource(R.string.pref_version_name),
                            summary = versionName,
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

@Composable
private fun GroupedSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun GroupedTextContent(
    title: String,
    summary: String? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(verticalArrangement = Arrangement.Center) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (summary != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = summaryColor,
            )
        }
    }
}

@Composable
private fun AimSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary.toArgb()
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outline.toArgb()
    AndroidView(
        modifier = Modifier.padding(end = 2.dp),
        factory = { context ->
            MaterialSwitch(context).apply {
                trackTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    ), intArrayOf(primaryColor, surfaceVariantColor)
                )
                thumbTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    ), intArrayOf(onPrimaryColor, outlineColor)
                )
                trackDecorationTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    ), intArrayOf(primaryColor, outlineColor)
                )
            }
        },
        update = { switchView ->
            switchView.isChecked = checked
            switchView.isEnabled = enabled
            switchView.setOnCheckedChangeListener { _, isChecked ->
                if (enabled) onCheckedChange(isChecked)
            }
        },
    )
}
