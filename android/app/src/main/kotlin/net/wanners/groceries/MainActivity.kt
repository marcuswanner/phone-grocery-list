package net.wanners.groceries

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user can deny; service still runs but no notification visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        setContent { App() }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun App() {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold { padding -> Body(padding) }
        }
    }
}

@Composable
private fun Body(padding: PaddingValues) {
    val state by ServerHolder.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Groceries",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (state.running) "Server running" else "Server stopped",
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (state.running) {
            val mdns = "http://${state.mdnsHost ?: "groceries.local"}:${state.port}"
            UrlRow(mdns, "Open from Mac (Bonjour)") { copy(ctx, mdns) }
            state.ips.forEach { ip ->
                val u = "http://$ip:${state.port}"
                UrlRow(u, "LAN IP") { copy(ctx, u) }
            }
            Text(
                "${state.itemCount} item${if (state.itemCount == 1) "" else "s"} • running for ${uptime(state.startedAt)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (state.running) {
            Button(
                onClick = { openInBrowser(ctx, "http://localhost:${ServerService.PORT}") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit list")
            }
            OutlinedButton(
                onClick = { ServerService.stop(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Stop server")
            }
        } else {
            Button(
                onClick = {
                    ServerService.start(ctx)
                    scope.launch {
                        ServerHolder.state.first { it.running }
                        openInBrowser(ctx, "http://localhost:${ServerService.PORT}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start server") }
        }

        OutlinedButton(
            onClick = { requestBatteryExemption(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Disable battery optimization")
        }
    }
}

@Composable
private fun UrlRow(url: String, label: String, onCopy: () -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                url,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onCopy) { Text("Copy") }
        }
    }
}

private fun openInBrowser(ctx: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("URL", text))
}

private fun requestBatteryExemption(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

private fun uptime(startedAt: Long): String {
    if (startedAt == 0L) return "0s"
    val ms = System.currentTimeMillis() - startedAt
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
    }
}
