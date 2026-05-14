package net.wanners.groceries

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val store = Store(parsed.dataFile)
    val subnets = if (parsed.lanOnly) LanDiscovery.localLanSubnets() else emptyList()
    val server = embeddedServer(CIO, port = parsed.port, host = parsed.host) {
        groceriesModule(store, subnets)
    }
    server.start(wait = false)
    val actualPort = kotlinx.coroutines.runBlocking {
        server.resolvedConnectors().firstOrNull()?.port
    } ?: parsed.port
    println("READY http://${parsed.host}:$actualPort")
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(500, 1_000)
    })
    Thread.currentThread().join()
}

private data class CliArgs(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val dataFile: File = File(System.getProperty("java.io.tmpdir"), "groceries-items.json"),
    val lanOnly: Boolean = false, // off by default for the standalone runner (e2e tests use loopback)
)

private fun parseArgs(args: Array<String>): CliArgs {
    var host = "127.0.0.1"
    var port = 8080
    var data = File(System.getProperty("java.io.tmpdir"), "groceries-items.json")
    var lanOnly = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data" -> { data = File(args[++i]) }
            "--lan-only" -> { lanOnly = true }
        }
        i++
    }
    return CliArgs(host, port, data, lanOnly)
}
