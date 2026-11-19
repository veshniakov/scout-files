package dev.scout.files.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.WebDavPreset
import dev.scout.files.core.security.CredentialVault
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.core.vfs.di.IoDispatcher
import dev.scout.files.feature.connections.model.ConnectionFormState
import dev.scout.files.feature.connections.model.ConnectionTestState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ServerConnectionViewModel @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
    private val vfsRouter: VfsRouter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _formState = MutableStateFlow(ConnectionFormState())
    val formState: StateFlow<ConnectionFormState> = _formState.asStateFlow()

    fun init(connectionToEdit: ServerConnection?) {
        if (connectionToEdit == null) {
            _formState.value = ConnectionFormState()
        } else {
            viewModelScope.launch(ioDispatcher) {
                val creds = credentialVault.getCredentials(connectionToEdit.id)
                _formState.value = ConnectionFormState.fromConnection(connectionToEdit, creds)
            }
        }
    }

    fun updateName(name: String) {
        _formState.update { it.copy(name = name, errorMessage = null) }
    }

    fun updateProtocol(protocol: NetworkProtocol) {
        _formState.update { current ->
            val defaultPort = protocol.defaultPort.toString()
            val useTls = when (protocol) {
                NetworkProtocol.FTP -> false
                NetworkProtocol.FTPS, NetworkProtocol.WEBDAV, NetworkProtocol.S3, NetworkProtocol.GDRIVE -> true
                else -> current.useTls
            }
            val defaultShare = when (protocol) {
                NetworkProtocol.SMB -> if (current.shareOrBucket.isBlank()) "shared" else current.shareOrBucket
                NetworkProtocol.S3 -> if (current.shareOrBucket.isBlank()) "bucket" else current.shareOrBucket
                else -> current.shareOrBucket
            }
            val defaultAuthType = when (protocol) {
                NetworkProtocol.GDRIVE -> AuthType.OAUTH2
                NetworkProtocol.S3 -> AuthType.ACCESS_KEY
                else -> if (current.authType == AuthType.OAUTH2 || current.authType == AuthType.ACCESS_KEY) AuthType.PASSWORD else current.authType
            }

            current.copy(
                protocol = protocol,
                portText = defaultPort,
                useTls = useTls,
                shareOrBucket = defaultShare,
                authType = defaultAuthType,
                testState = ConnectionTestState.Idle,
                errorMessage = null
            )
        }
    }

    fun updateHost(host: String) {
        _formState.update { it.copy(host = host, errorMessage = null) }
    }

    fun updatePortText(portText: String) {
        _formState.update { it.copy(portText = portText, errorMessage = null) }
    }

    fun updateUsername(username: String) {
        _formState.update { it.copy(username = username, errorMessage = null) }
    }

    fun updateAuthType(authType: AuthType) {
        _formState.update { it.copy(authType = authType, errorMessage = null) }
    }

    fun updatePassword(password: String) {
        _formState.update { it.copy(password = password, errorMessage = null) }
    }

    fun togglePasswordVisibility() {
        _formState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun updatePrivateKeyPem(pem: String) {
        _formState.update { it.copy(privateKeyPem = pem, errorMessage = null) }
    }

    fun updatePrivateKeyPassphrase(passphrase: String) {
        _formState.update { it.copy(privateKeyPassphrase = passphrase, errorMessage = null) }
    }

    fun togglePassphraseVisibility() {
        _formState.update { it.copy(isPassphraseVisible = !it.isPassphraseVisible) }
    }

    fun updateShareOrBucket(shareOrBucket: String) {
        _formState.update { it.copy(shareOrBucket = shareOrBucket, errorMessage = null) }
    }

    fun updateDomainOrRegion(domainOrRegion: String) {
        _formState.update { it.copy(domainOrRegion = domainOrRegion, errorMessage = null) }
    }

    fun updateRootPath(rootPath: String) {
        _formState.update { it.copy(rootPath = rootPath, errorMessage = null) }
    }

    fun updateUseTls(useTls: Boolean) {
        _formState.update { current ->
            val updatedProtocol = if (current.protocol == NetworkProtocol.FTP && useTls) {
                NetworkProtocol.FTPS
            } else if (current.protocol == NetworkProtocol.FTPS && !useTls) {
                NetworkProtocol.FTP
            } else {
                current.protocol
            }
            val portText = if (updatedProtocol != current.protocol) {
                updatedProtocol.defaultPort.toString()
            } else {
                current.portText
            }
            current.copy(
                protocol = updatedProtocol,
                useTls = useTls,
                portText = portText,
                errorMessage = null
            )
        }
    }

    fun updateRequireBiometric(requireBiometric: Boolean) {
        _formState.update { it.copy(requireBiometric = requireBiometric) }
    }

    fun updateWebDavPreset(preset: WebDavPreset) {
        _formState.update { current ->
            when (preset) {
                WebDavPreset.YANDEX_DISK -> current.copy(
                    webDavPreset = preset,
                    host = preset.defaultHost,
                    portText = preset.defaultPort.toString(),
                    rootPath = preset.defaultRootPath,
                    useTls = preset.useTls,
                    errorMessage = null
                )
                WebDavPreset.NEXTCLOUD -> current.copy(
                    webDavPreset = preset,
                    portText = preset.defaultPort.toString(),
                    rootPath = preset.defaultRootPath,
                    useTls = preset.useTls,
                    errorMessage = null
                )
                WebDavPreset.OWNCLOUD -> current.copy(
                    webDavPreset = preset,
                    portText = preset.defaultPort.toString(),
                    rootPath = preset.defaultRootPath,
                    useTls = preset.useTls,
                    errorMessage = null
                )
                WebDavPreset.CUSTOM -> current.copy(
                    webDavPreset = preset,
                    errorMessage = null
                )
            }
        }
    }

    fun updateIsAnonymous(isAnonymous: Boolean) {
        _formState.update { current ->
            current.copy(
                isAnonymous = isAnonymous,
                authType = if (isAnonymous) AuthType.GUEST else AuthType.PASSWORD,
                errorMessage = null
            )
        }
    }

    fun updateOAuthClientId(clientId: String) {
        _formState.update { it.copy(oauthClientId = clientId, errorMessage = null) }
    }

    fun updateOAuthRefreshToken(token: String) {
        _formState.update { it.copy(oauthRefreshToken = token, errorMessage = null) }
    }

    fun updateOAuthAccessToken(token: String) {
        _formState.update { it.copy(oauthAccessToken = token, errorMessage = null) }
    }

    fun updateS3SecretKey(secret: String) {
        _formState.update { it.copy(s3SecretKey = secret, errorMessage = null) }
    }

    fun toggleS3SecretVisibility() {
        _formState.update { it.copy(isS3SecretVisible = !it.isS3SecretVisible) }
    }

    fun testConnection() {
        val current = _formState.value
        val validationErr = validateForm(current)
        if (validationErr != null) {
            _formState.update {
                it.copy(
                    errorMessage = validationErr,
                    testState = ConnectionTestState.Error(validationErr)
                )
            }
            return
        }

        _formState.update {
            it.copy(
                testState = ConnectionTestState.Testing,
                errorMessage = null
            )
        }

        viewModelScope.launch(ioDispatcher) {
            val tempId = "test_${UUID.randomUUID()}"
            val tempConnection = current.toServerConnection(connectionId = tempId)
            val tempCredentials = current.toServerCredentials()

            try {
                metadataRepository.saveServerConnection(tempConnection)
                credentialVault.storeCredentials(tempId, tempCredentials)

                val nodes = vfsRouter.listDirectory(tempConnection.rootVfsUri())
                _formState.update {
                    it.copy(testState = ConnectionTestState.Success(nodes.size))
                }
            } catch (e: Exception) {
                _formState.update {
                    it.copy(testState = ConnectionTestState.Error(e.message ?: "Connection test failed"))
                }
            } finally {
                runCatching { metadataRepository.deleteServerConnection(tempId) }
                runCatching { credentialVault.deleteCredentials(tempId) }
            }
        }
    }

    fun saveConnection(onSuccess: (ServerConnection) -> Unit) {
        val current = _formState.value
        val validationErr = validateForm(current)
        if (validationErr != null) {
            _formState.update { it.copy(errorMessage = validationErr) }
            return
        }

        _formState.update { it.copy(isSaving = true) }

        viewModelScope.launch(ioDispatcher) {
            try {
                val connection = current.toServerConnection()
                val credentials = current.toServerCredentials()

                metadataRepository.saveServerConnection(connection)
                credentialVault.storeCredentials(connection.id, credentials)

                withContext(Dispatchers.Main) {
                    _formState.update { it.copy(isSaving = false) }
                    onSuccess(connection)
                }
            } catch (e: Exception) {
                _formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.message ?: "Failed to save connection"
                    )
                }
            }
        }
    }

    private fun validateForm(state: ConnectionFormState): String? {
        if (state.name.isBlank() && state.host.isBlank()) {
            return "Connection name or host is required"
        }
        if (state.protocol != NetworkProtocol.GDRIVE && state.host.isBlank()) {
            return "Host / Server address is required"
        }
        val port = state.portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return "Invalid port number (must be 1-65535)"
        }
        if (state.protocol == NetworkProtocol.SMB && state.shareOrBucket.isBlank()) {
            return "SMB share name is required"
        }
        if (state.protocol == NetworkProtocol.S3 && state.shareOrBucket.isBlank()) {
            return "S3 bucket name is required"
        }
        return null
    }
}
