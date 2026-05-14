package net.wanners.groceries

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerState(
    val running: Boolean = false,
    val port: Int = 8080,
    val ips: List<String> = emptyList(),
    val mdnsHost: String? = null,
    val itemCount: Int = 0,
    val startedAt: Long = 0L,
)

object ServerHolder {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun mutate(transform: (ServerState) -> ServerState) {
        _state.value = transform(_state.value)
    }
}
