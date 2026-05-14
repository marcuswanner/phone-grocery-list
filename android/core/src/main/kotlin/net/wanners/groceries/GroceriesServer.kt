package net.wanners.groceries

import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer

class GroceriesServer(private val store: Store) {
    private var engine: ApplicationEngine? = null
    private var allowedSubnets: List<Subnet> = emptyList()

    fun start(port: Int, host: String = "0.0.0.0", lanOnly: Boolean = true) {
        if (engine != null) return
        // Capture the current LAN subnets at start. If the device's WiFi changes later
        // the filter still reflects the network the server was bound to, so requests
        // from a different subnet are rejected. Loopback is always allowed.
        allowedSubnets = if (lanOnly) LanDiscovery.localLanSubnets() else emptyList()
        engine = embeddedServer(CIO, port = port, host = host) {
            groceriesModule(store, allowedSubnets)
        }.also { it.start(wait = false) }
    }

    fun currentAllowedSubnets(): List<Subnet> = allowedSubnets

    fun stop(gracePeriodMs: Long = 500, timeoutMs: Long = 1_000) {
        engine?.stop(gracePeriodMs, timeoutMs)
        engine = null
        allowedSubnets = emptyList()
    }
}
