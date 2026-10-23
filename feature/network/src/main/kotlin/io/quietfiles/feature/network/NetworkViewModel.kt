package io.quietfiles.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.quietfiles.core.common.IoDispatcher
import io.quietfiles.core.model.network.Connection
import io.quietfiles.core.model.network.ConnectionStatus
import io.quietfiles.core.model.network.NetworkPaths
import io.quietfiles.core.model.network.RemoteProblem
import io.quietfiles.core.network.ConnectionsRepository
import io.quietfiles.core.network.RemoteSessions
import io.quietfiles.core.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ServerItem(val connection: Connection, val status: ConnectionStatus)

/** Проблема подключения, которую надо показать пользователю (ключ, сертификат, вход). */
data class PendingProblem(val connection: Connection, val problem: RemoteProblem)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val repository: ConnectionsRepository,
    private val sessions: RemoteSessions,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val servers: StateFlow<List<ServerItem>?> = combine(repository.connections, sessions.statuses) { list, statuses ->
        list.map { ServerItem(it, statuses[it.id] ?: ConnectionStatus.UNKNOWN) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _opening = MutableStateFlow<Long?>(null)
    val opening: StateFlow<Long?> = _opening

    private val _problem = MutableStateFlow<PendingProblem?>(null)
    val problem: StateFlow<PendingProblem?> = _problem

    private val opened = Channel<String>(Channel.BUFFERED)
    val openFolder: Flow<String> = opened.receiveAsFlow()

    /** Подключается и открывает начальную папку сервера. */
    fun open(connection: Connection) {
        if (_opening.value != null) return
        viewModelScope.launch {
            _opening.value = connection.id
            try {
                val start = withContext(io) { sessions.startPath(connection.id) }
                opened.send(NetworkPaths.pathOf(connection.id, start))
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException.Remote) {
                _problem.value = PendingProblem(connection, e.problem)
            } catch (e: Exception) {
                _problem.value = PendingProblem(connection, RemoteProblem.Protocol(e.message ?: e.javaClass.simpleName))
            } finally {
                _opening.value = null
            }
        }
    }

    fun dismissProblem() {
        _problem.value = null
    }

    /** Пользователь сверил отпечаток или сертификат и доверился ему (F16.4, F16.5). */
    fun trust(pending: PendingProblem) {
        _problem.value = null
        viewModelScope.launch {
            val id = pending.connection.id
            when (val p = pending.problem) {
                is RemoteProblem.UnknownHostKey -> repository.trustHostKey(id, p.fingerprint)
                is RemoteProblem.HostKeyChanged -> repository.trustHostKey(id, p.actual)
                is RemoteProblem.UntrustedCertificate -> repository.pinCertificate(id, p.certificate.spkiSha256)
                is RemoteProblem.CertificateChanged -> repository.pinCertificate(id, p.certificate.spkiSha256)
                else -> return@launch
            }
            sessions.disconnect(id)
            repository.get(id)?.let(::open)
        }
    }

    fun disconnect(connection: Connection) = sessions.disconnect(connection.id)

    fun delete(connection: Connection) {
        viewModelScope.launch {
            sessions.disconnect(connection.id)
            repository.delete(connection.id)
        }
    }
}
