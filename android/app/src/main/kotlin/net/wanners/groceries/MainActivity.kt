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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user can deny; service still runs but no notification visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        ServerService.start(this)
        setContent { App() }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Screen { Main, Settings }

@Composable
private fun App() {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by remember { mutableStateOf(Screen.Main) }
            BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Main }
            when (screen) {
                Screen.Main -> MainScreen(onOpenSettings = { screen = Screen.Settings })
                Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
            }
        }
    }
}

@Composable
private fun MainScreen(onOpenSettings: () -> Unit) {
    val state by ServerHolder.state.collectAsState()
    val ctx = LocalContext.current

    // Hold the WebView across recompositions: a fresh instance per render would
    // re-fetch the page and drop the SSE subscription on every theme/state change.
    // Survives rotation too because the activity's configChanges flag keeps the
    // composition alive.
    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Without these two the WebView leaves html/body with 0 resolved height —
            // `height: 100%` chains have no parent dimension to inherit, so `main`'s
            // `flex: 1` collapses to 0 and the list (5×56px) renders off-viewport into
            // an unscrollable region. useWideViewPort honours the <meta name=viewport>
            // tag; loadWithOverviewMode initial-scales the page to fit the screen.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val host = req.url.host ?: return false
                    return if (host == "localhost" || host == "127.0.0.1") {
                        false // keep loopback navigation inside the WebView
                    } else {
                        openInBrowser(view.context, req.url.toString())
                        true
                    }
                }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    // Main frame error usually means the server hasn't started yet (initial boot)
                    // or just stopped. Retry; the state-driven reload below covers the explicit
                    // start-from-Settings path.
                    if (req.isForMainFrame) view.postDelayed({ view.reload() }, 1_500)
                }
            }
            loadUrl("http://localhost:${ServerService.PORT}/")
        }
    }

    // Reload when the server transitions to running — covers "stop in Settings → back to Main
    // → Start in Settings → back to Main" without waiting for the next onReceivedError retry.
    LaunchedEffect(state.running) {
        if (state.running) webView.reload()
    }

    Column(Modifier.fillMaxSize()) {
        // No title text here — the WebView's own <h1>Groceries</h1> is the page's
        // visual title (and is what desktop browsers see too). The Compose bar is
        // a minimal Android chrome strip whose only job is to host the settings gear.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Text("⚙", fontSize = 22.sp)
            }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.running) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Server not running")
                        state.error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onOpenSettings) { Text("Open settings") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val state by ServerHolder.state.collectAsState()
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBack) { Text("Done") }
        }

        Text(
            if (state.running) "Server running" else "Server stopped",
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (state.running) {
            val mdns = "http://${state.mdnsHost ?: "groceries.local"}:${state.port}"
            UrlRow(mdns, "Open from another device (Bonjour)") { copy(ctx, mdns) }
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
            OutlinedButton(
                onClick = { openInBrowser(ctx, "http://localhost:${ServerService.PORT}") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open in external browser") }
            OutlinedButton(
                onClick = { ServerService.stop(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop server") }
        } else {
            Button(
                onClick = { ServerService.start(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start server") }
        }

        OutlinedButton(
            onClick = { requestBatteryExemption(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disable battery optimization") }
    }
}

@Composable
private fun UrlRow(url: String, label: String, onCopy: () -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
