package cc.tomko.outify.ui.components.bottomsheet

import cc.tomko.outify.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ui.notifications.InAppNotificationController
import cc.tomko.outify.ui.viewmodel.bottomsheet.CreatePlaylistViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlaylistBottomSheet(
    viewModel: CreatePlaylistViewModel,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
    onDeleted: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    playlistId: String? = null,
    initialName: String = "",
    initialDescription: String = "",
    initialCollaborative: Boolean = false,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val isEditMode = playlistId != null

    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.result.collect { result ->
            isSaving = false
            result.fold(
                onSuccess = { id ->
                    if (isEditMode) {
                        InAppNotificationController.show(context.getString(R.string.toast_playlist_updated), durationMillis = 2000L)
                    }
                    onDismiss()
                    onCreated(id)
                },
                onFailure = { error ->
                    val message = error.message ?: context.getString(R.string.error_unknown)
                    InAppNotificationController.show(message, durationMillis = 3000L)
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.deleted.collect { result ->
            isSaving = false
            result.fold(
                onSuccess = { id ->
                    InAppNotificationController.show(context.getString(R.string.toast_playlist_deleted), durationMillis = 2000L)
                    onDismiss()
                    onDeleted(id)
                },
                onFailure = {
                    InAppNotificationController.show(context.getString(R.string.error_playlist_delete), durationMillis = 3000L)
                }
            )
        }
    }

    OutifyModalBottomSheet(
        onDismissRequest = {
            if (!isSaving) {
                coroutineScope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier,
    ) {
        CreatePlaylistSheetContent(
            playlistId = playlistId,
            initialName = initialName,
            initialDescription = initialDescription,
            initialCollaborative = initialCollaborative,
            isSaving = isSaving,
            loadPublic = viewModel::loadPublic,
            onCancel = {
                coroutineScope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            },
            onSubmit = { name, description, isPublic, isCollaborative ->
                if (isSaving) return@CreatePlaylistSheetContent
                isSaving = true
                if (playlistId != null) {
                    viewModel.modifyPlaylist(
                        playlistId = playlistId,
                        name = name,
                        description = description,
                        isPublic = isPublic,
                        isCollaborative = isCollaborative,
                    )
                } else {
                    viewModel.createPlaylist(
                        name = name,
                        description = description,
                        isPublic = isPublic ?: true,
                        isCollaborative = isCollaborative,
                    )
                }
            },
            onDelete = { id ->
                if (isSaving) return@CreatePlaylistSheetContent
                isSaving = true
                viewModel.deletePlaylist(id)
            },
        )
    }
}

/**
 * The form of [CreatePlaylistBottomSheet], without the view model, so it can be tested on its own.
 *
 * @param loadPublic Fetches the visibility of an existing playlist, null when unknown.
 * @param onSubmit Gets the trimmed name and description. The visibility is null when
 * the user did not change it, so it is left as it is on the server.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CreatePlaylistSheetContent(
    playlistId: String?,
    initialName: String,
    initialDescription: String,
    initialCollaborative: Boolean,
    isSaving: Boolean,
    loadPublic: suspend (String) -> Boolean?,
    onCancel: () -> Unit,
    onSubmit: (name: String, description: String, isPublic: Boolean?, isCollaborative: Boolean) -> Unit,
    onDelete: (playlistId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEditMode = playlistId != null

    var name by rememberSaveable(playlistId) { mutableStateOf(initialName) }
    var description by rememberSaveable(playlistId) { mutableStateOf(initialDescription) }
    // Null while the visibility of an existing playlist is unknown
    var isPublic by rememberSaveable(playlistId) { mutableStateOf<Boolean?>(if (isEditMode) null else true) }
    var visibilityLoaded by remember(playlistId) { mutableStateOf(!isEditMode) }
    // Visibility is only sent when the user changed it
    var publicChanged by rememberSaveable(playlistId) { mutableStateOf(!isEditMode) }
    var isCollaborative by rememberSaveable(playlistId) { mutableStateOf(initialCollaborative) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        if (playlistId == null) return@LaunchedEffect
        val loaded = loadPublic(playlistId)
        if (!publicChanged) isPublic = loaded
        visibilityLoaded = true
    }

    if (confirmDelete && playlistId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.playlist_delete_title)) },
            text = { Text(stringResource(R.string.playlist_delete_message, initialName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(playlistId)
                }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // The fields scroll, the buttons stay in reach below them.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                        .size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = if (isEditMode) stringResource(R.string.createplaylist_edit_title) else stringResource(R.string.createplaylist_create_title),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    fontWeight = FontWeight.Black,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.playlist_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.playlist_desc_optional)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.playlist_public),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = when {
                            !visibilityLoaded -> stringResource(R.string.playlist_public_loading)
                            isPublic == null -> stringResource(R.string.playlist_public_unknown)
                            isPublic == true -> stringResource(R.string.playlist_public_anyone)
                            else -> stringResource(R.string.playlist_public_only_you)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isPublic == true,
                    onCheckedChange = {
                        isPublic = it
                        publicChanged = true
                    },
                    enabled = !isCollaborative && isPublic != null,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.playlist_collaborative),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.playlist_others),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isCollaborative,
                    onCheckedChange = { collaborative ->
                        isCollaborative = collaborative
                        if (collaborative && isPublic != false) {
                            isPublic = false
                            publicChanged = true
                        }
                    },
                )
            }

            if (isEditMode) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.playlist_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !isSaving,
            ) {
                Text(stringResource(R.string.common_cancel))
            }

            Button(
                onClick = {
                    if (isSaving) return@Button
                    onSubmit(
                        name.trim(),
                        description.trim(),
                        isPublic.takeIf { publicChanged },
                        isCollaborative,
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank() && !isSaving,
            ) {
                Text(
                    when {
                        isSaving && isEditMode -> stringResource(R.string.createplaylist_saving)
                        isSaving -> stringResource(R.string.createplaylist_creating)
                        isEditMode -> stringResource(R.string.common_save)
                        else -> stringResource(R.string.common_create)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}
