package dev.scout.files.feature.connections.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.WebDavPreset
import dev.scout.files.feature.connections.R
import dev.scout.files.feature.connections.ServerConnectionViewModel
import dev.scout.files.feature.connections.model.ConnectionFormState
import dev.scout.files.feature.connections.model.ConnectionTestState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConnectionSheet(
    connectionToEdit: ServerConnection? = null,
    onDismiss: () -> Unit,
    onSaved: (ServerConnection) -> Unit,
    viewModel: ServerConnectionViewModel = viewModel()
) {
    LaunchedEffect(connectionToEdit) {
        viewModel.init(connectionToEdit)
    }

    val formState by viewModel.formState.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .heightIn(max = 700.dp),
        title = {
            Text(
                text = if (formState.isEditMode) {
                    stringResource(R.string.connection_edit_title)
                } else {
                    stringResource(R.string.connection_new_title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Protocol Selector
                Text(
                    text = stringResource(R.string.connection_protocol),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val protocolScrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(protocolScrollState),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NetworkProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = formState.protocol == proto,
                            onClick = { viewModel.updateProtocol(proto) },
                            label = { Text(proto.displayName) },
                            leadingIcon = if (formState.protocol == proto) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Connection Name
                OutlinedTextField(
                    value = formState.name,
                    onValueChange = viewModel::updateName,
                    label = { Text(stringResource(R.string.connection_name)) },
                    placeholder = { Text(stringResource(R.string.connection_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Dynamic Protocol Specific Fields
                when (formState.protocol) {
                    NetworkProtocol.SFTP -> SftpFields(formState, viewModel)
                    NetworkProtocol.SMB -> SmbFields(formState, viewModel)
                    NetworkProtocol.FTP, NetworkProtocol.FTPS -> FtpFields(formState, viewModel)
                    NetworkProtocol.WEBDAV -> WebDavFields(formState, viewModel)
                    NetworkProtocol.GDRIVE -> GDriveFields(formState, viewModel)
                    NetworkProtocol.S3 -> S3Fields(formState, viewModel)
                }

                // Security: Biometric Protection
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.connection_require_biometric),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.connection_require_biometric_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = formState.requireBiometric,
                        onCheckedChange = viewModel::updateRequireBiometric
                    )
                }

                // Error message
                if (formState.errorMessage != null && formState.testState !is ConnectionTestState.Error) {
                    Text(
                        text = formState.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Inline Test Connection Status
                AnimatedVisibility(visible = formState.testState !is ConnectionTestState.Idle) {
                    when (val testState = formState.testState) {
                        is ConnectionTestState.Testing -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = stringResource(R.string.connection_testing),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is ConnectionTestState.Success -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(R.string.connection_test_success, testState.itemCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                        is ConnectionTestState.Error -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(R.string.connection_test_failed, testState.message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        ConnectionTestState.Idle -> Unit
                    }
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    enabled = !formState.isSaving && formState.testState !is ConnectionTestState.Testing
                ) {
                    Text(stringResource(R.string.connection_test_button))
                }
                Button(
                    onClick = {
                        viewModel.saveConnection { savedConn ->
                            onSaved(savedConn)
                        }
                    },
                    enabled = !formState.isSaving
                ) {
                    if (formState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.connection_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.connection_cancel))
            }
        }
    )
}

@Composable
private fun SftpFields(formState: ConnectionFormState, viewModel: ServerConnectionViewModel) {
    HostAndPortRow(formState, viewModel)

    OutlinedTextField(
        value = formState.username,
        onValueChange = viewModel::updateUsername,
        label = { Text(stringResource(R.string.connection_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = formState.authType == AuthType.PASSWORD,
            onClick = { viewModel.updateAuthType(AuthType.PASSWORD) },
            label = { Text(stringResource(R.string.connection_auth_password)) }
        )
        FilterChip(
            selected = formState.authType == AuthType.SSH_KEY,
            onClick = { viewModel.updateAuthType(AuthType.SSH_KEY) },
            label = { Text(stringResource(R.string.connection_auth_ssh_key)) }
        )
    }

    if (formState.authType == AuthType.PASSWORD) {
        PasswordField(
            value = formState.password,
            onValueChange = viewModel::updatePassword,
            label = stringResource(R.string.connection_password),
            isVisible = formState.isPasswordVisible,
            onToggleVisibility = viewModel::togglePasswordVisibility
        )
    } else {
        OutlinedTextField(
            value = formState.privateKeyPem,
            onValueChange = viewModel::updatePrivateKeyPem,
            label = { Text(stringResource(R.string.connection_ssh_key_pem)) },
            placeholder = { Text(stringResource(R.string.connection_ssh_key_pem_hint)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        PasswordField(
            value = formState.privateKeyPassphrase,
            onValueChange = viewModel::updatePrivateKeyPassphrase,
            label = stringResource(R.string.connection_ssh_passphrase),
            isVisible = formState.isPassphraseVisible,
            onToggleVisibility = viewModel::togglePassphraseVisibility
        )
    }

    OutlinedTextField(
        value = formState.rootPath,
        onValueChange = viewModel::updateRootPath,
        label = { Text(stringResource(R.string.connection_root_path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SmbFields(formState: ConnectionFormState, viewModel: ServerConnectionViewModel) {
    HostAndPortRow(formState, viewModel)

    OutlinedTextField(
        value = formState.shareOrBucket,
        onValueChange = viewModel::updateShareOrBucket,
        label = { Text(stringResource(R.string.connection_smb_share)) },
        placeholder = { Text(stringResource(R.string.connection_smb_share_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = !formState.isAnonymous,
            onClick = { viewModel.updateIsAnonymous(false) },
            label = { Text(stringResource(R.string.connection_auth_password)) }
        )
        FilterChip(
            selected = formState.isAnonymous,
            onClick = { viewModel.updateIsAnonymous(true) },
            label = { Text(stringResource(R.string.connection_auth_guest)) }
        )
    }

    if (!formState.isAnonymous) {
        OutlinedTextField(
            value = formState.username,
            onValueChange = viewModel::updateUsername,
            label = { Text(stringResource(R.string.connection_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = formState.domainOrRegion,
            onValueChange = viewModel::updateDomainOrRegion,
            label = { Text(stringResource(R.string.connection_smb_domain)) },
            placeholder = { Text(stringResource(R.string.connection_smb_domain_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        PasswordField(
            value = formState.password,
            onValueChange = viewModel::updatePassword,
            label = stringResource(R.string.connection_password),
            isVisible = formState.isPasswordVisible,
            onToggleVisibility = viewModel::togglePasswordVisibility
        )
    }

    OutlinedTextField(
        value = formState.rootPath,
        onValueChange = viewModel::updateRootPath,
        label = { Text(stringResource(R.string.connection_root_path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FtpFields(formState: ConnectionFormState, viewModel: ServerConnectionViewModel) {
    HostAndPortRow(formState, viewModel)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.connection_use_tls), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = formState.useTls,
            onCheckedChange = viewModel::updateUseTls
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.connection_anonymous), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = formState.isAnonymous,
            onCheckedChange = viewModel::updateIsAnonymous
        )
    }

    if (!formState.isAnonymous) {
        OutlinedTextField(
            value = formState.username,
            onValueChange = viewModel::updateUsername,
            label = { Text(stringResource(R.string.connection_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        PasswordField(
            value = formState.password,
            onValueChange = viewModel::updatePassword,
            label = stringResource(R.string.connection_password),
            isVisible = formState.isPasswordVisible,
            onToggleVisibility = viewModel::togglePasswordVisibility
        )
    }

    OutlinedTextField(
        value = formState.rootPath,
        onValueChange = viewModel::updateRootPath,
        label = { Text(stringResource(R.string.connection_root_path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun WebDavFields(formState: ConnectionFormState, viewModel: ServerConnectionViewModel) {
    // Preset Selector
    Text(
        text = stringResource(R.string.connection_webdav_preset),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val presetScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(presetScroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WebDavPreset.entries.forEach { preset ->
            val label = when (preset) {
                WebDavPreset.CUSTOM -> stringResource(R.string.connection_webdav_preset_custom)
                WebDavPreset.NEXTCLOUD -> stringResource(R.string.connection_webdav_preset_nextcloud)
                WebDavPreset.OWNCLOUD -> stringResource(R.string.connection_webdav_preset_owncloud)
                WebDavPreset.YANDEX_DISK -> stringResource(R.string.connection_webdav_preset_yandex)
            }
            FilterChip(
                selected = formState.webDavPreset == preset,
                onClick = { viewModel.updateWebDavPreset(preset) },
                label = { Text(label) }
            )
        }
    }

    if (formState.webDavPreset == WebDavPreset.YANDEX_DISK) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.connection_yandex_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    HostAndPortRow(formState, viewModel)

    OutlinedTextField(
        value = formState.username,
        onValueChange = viewModel::updateUsername,
        label = { Text(stringResource(R.string.connection_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    PasswordField(
        value = formState.password,
        onValueChange = viewModel::updatePassword,
        label = stringResource(R.string.connection_password),
        isVisible = formState.isPasswordVisible,
        onToggleVisibility = viewModel::togglePasswordVisibility
    )

    OutlinedTextField(
        value = formState.rootPath,
        onValueChange = viewModel::updateRootPath,
        label = { Text(stringResource(R.string.connection_root_path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.connection_use_tls), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = formState.useTls,
            onCheckedChange = viewModel::updateUseTls
        )
    }
}

@Composable
private fun GDriveFields(formState: ConnectionFormState, viewModel: ServerConnectionViewModel) {
    OutlinedTextField(
        value = formState.oauthClientId,
        onValueChange = viewModel::updateOAuthClientId,
        label = { Text(stringResource(R.string.connection_gdrive_client_id)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = formState.oauthRefreshToken,
        onValueChange = viewModel::updateOAuthRefreshToken,
        label = { Text(stringResource(R.string.connection_gdrive_refresh_token)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = formState.rootPath,
        onValueChange = viewModel::updateRootPath,
        label = { Text(stringResource(R.string.connection_root_path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun S3Fields(formState: ConnectionFormState, viewModel: ServerConnectionViewModel) {
    HostAndPortRow(
        formState = formState,
        viewModel = viewModel,
        hostLabel = stringResource(R.string.connection_s3_endpoint),
        hostHint = stringResource(R.string.connection_s3_endpoint_hint)
    )

    OutlinedTextField(
        value = formState.shareOrBucket,
        onValueChange = viewModel::updateShareOrBucket,
        label = { Text(stringResource(R.string.connection_s3_bucket)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = formState.domainOrRegion,
        onValueChange = viewModel::updateDomainOrRegion,
        label = { Text(stringResource(R.string.connection_s3_region)) },
        placeholder = { Text(stringResource(R.string.connection_s3_region_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = formState.username,
        onValueChange = viewModel::updateUsername,
        label = { Text(stringResource(R.string.connection_s3_access_key)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    PasswordField(
        value = formState.s3SecretKey,
        onValueChange = viewModel::updateS3SecretKey,
        label = stringResource(R.string.connection_s3_secret_key),
        isVisible = formState.isS3SecretVisible,
        onToggleVisibility = viewModel::toggleS3SecretVisibility
    )

    OutlinedTextField(
        value = formState.rootPath,
        onValueChange = viewModel::updateRootPath,
        label = { Text(stringResource(R.string.connection_root_path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.connection_use_tls), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = formState.useTls,
            onCheckedChange = viewModel::updateUseTls
        )
    }
}

@Composable
private fun HostAndPortRow(
    formState: ConnectionFormState,
    viewModel: ServerConnectionViewModel,
    hostLabel: String = stringResource(R.string.connection_host),
    hostHint: String = stringResource(R.string.connection_host_hint)
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = formState.host,
            onValueChange = viewModel::updateHost,
            label = { Text(hostLabel) },
            placeholder = { Text(hostHint) },
            singleLine = true,
            modifier = Modifier.weight(0.72f)
        )
        OutlinedTextField(
            value = formState.portText,
            onValueChange = viewModel::updatePortText,
            label = { Text(stringResource(R.string.connection_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(0.28f)
        )
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
