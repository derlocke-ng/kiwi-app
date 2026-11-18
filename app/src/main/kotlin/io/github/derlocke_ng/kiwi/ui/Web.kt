package io.github.derlocke_ng.kiwi.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.core.Lookup
import io.github.derlocke_ng.kiwi.core.Tunnel
import io.github.derlocke_ng.kiwi.core.isOfFleet
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A page of the network, inside the app: for the one who runs it and for the people who use it. The phone does not know the fleet's CA, so a page
 * with a certificate from it would be turned away: here it is let in when [caPem] signed the
 * certificate for that very name, and the name is one of the network's ([ofNetwork]). Any
 * other page leaves for the phone's browser.
 *
 * The web view finds addresses with the phone's resolver, which on some phones gives apps a
 * blank address for a name of the network: the page is then refused. So the names of the
 * network ([names], as a web view's rules want them: `*.home`) go through a [Tunnel] on the
 * phone itself, which finds them with [lookup] as the app's own connections do.
 */
@Composable
fun Web(
    url: String,
    title: String,
    caPem: String,
    names: List<String>,
    ofNetwork: (host: String) -> Boolean,
    lookup: Lookup,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val isOfNetwork by rememberUpdatedState(ofNetwork)
    var progress by remember { mutableIntStateOf(0) }
    var at by remember { mutableStateOf(url) }
    var failed by remember { mutableStateOf<String?>(null) }
    var untrusted by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // a page that asks for a file to send
    var wanted by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { files ->
        wanted?.onReceiveValue(files.toTypedArray())
        wanted = null
    }

    val view = remember {
        newWebView(
            context,
            trusted = { host, cert -> isOfNetwork(host) && isOfFleet(caPem, cert, host) },
            ofNetwork = { isOfNetwork(it) },
            onProgress = { progress = it },
            onPage = { at = it; failed = null; untrusted = false },
            onFailed = { what, notTrusted -> failed = what; untrusted = notTrusted },
            onFiles = { callback ->
                wanted?.onReceiveValue(null)
                wanted = callback
                pick.launch("*/*")
            },
        )
    }
    DisposableEffect(view) {
        var gone = false
        val main = ContextCompat.getMainExecutor(context)
        val tunnel = if (names.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching { Tunnel(lookup) { host, port -> port == 443 && isOfNetwork(host) } }.getOrNull()
        } else {
            null
        }
        if (tunnel == null || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            view.loadUrl(url)
        } else {
            val rules = ProxyConfig.Builder().addProxyRule("127.0.0.1:${tunnel.port}")
            // only the names of the network go through the tunnel, where the web view can be told so
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
                names.forEach { rules.addBypassRule(it) }
                rules.setReverseBypassEnabled(true)
            }
            ProxyController.getInstance().setProxyOverride(rules.build(), main) { if (!gone) view.loadUrl(url) }
        }
        onDispose {
            gone = true
            if (tunnel != null && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(main) {}
            }
            tunnel?.close()
            wanted?.onReceiveValue(null)
            view.stopLoading()
            view.destroy()
        }
    }
    BackHandler { if (failed == null && view.canGoBack()) view.goBack() else onClose() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = Uri.parse(at).host.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { failed = null; view.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.in_browser)) },
                            onClick = { menu = false; context.openOutside(at) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_address)) },
                            onClick = {
                                menu = false
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, at))
                            },
                        )
                    }
                }
            }
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            val problem = failed
            if (problem != null) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.page_fails), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (untrusted) stringResource(R.string.page_not_trusted) else problem,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { context.openOutside(at) }) { Text(stringResource(R.string.in_browser)) }
                }
            } else {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Hand a page to the phone's browser, or a mail address or phone number to the app for it. */
internal fun Context.openOutside(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@SuppressLint("SetJavaScriptEnabled") // the network's services are web apps
private fun newWebView(
    context: Context,
    trusted: (host: String, cert: X509Certificate) -> Boolean,
    ofNetwork: (host: String) -> Boolean,
    onProgress: (Int) -> Unit,
    onPage: (String) -> Unit,
    onFailed: (what: String, notTrusted: Boolean) -> Unit,
    onFiles: (ValueCallback<Array<Uri>>) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // nothing of the phone: no files, no content of other apps, no second window
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportMultipleWindows(false)
    settings.setGeolocationEnabled(false)

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val to = request.url
            return when (to.scheme) {
                // inside the app only what is of the network; a link elsewhere is for the browser
                "https" -> if (!request.isForMainFrame || ofNetwork(to.host.orEmpty())) false else true.also { context.openOutside(to.toString()) }
                "mailto", "tel" -> true.also { context.openOutside(to.toString()) }
                else -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onPage(url)

        // nothing is let through on the phone's word: [trusted] checks the certificate itself
        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // whatever the phone found wrong: the certificate is checked anew, against the fleet's CA and the name
            val host = Uri.parse(error.url).host.orEmpty()
            val cert = error.certificate.x509()
            if (cert != null && trusted(host, cert)) {
                handler.proceed()
            } else {
                handler.cancel()
                if (error.url == view.url || view.url == null) onFailed(host, true)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onFailed(error.description?.toString().orEmpty(), false)
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            onFiles(callback)
            return true
        }
    }
    // what a page offers to keep goes to the browser: this app keeps no files
    setDownloadListener { from, _, _, _, _ -> context.openOutside(from) }
}

/** The certificate itself, which the phone hands over in its own wrapping. */
private fun SslCertificate.x509(): X509Certificate? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        x509Certificate
    } else {
        SslCertificate.saveState(this)?.getByteArray("x509-certificate")?.let {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(it)) as? X509Certificate
        }
    }
} catch (e: Exception) {
    null
}

/** Delete what the pages kept on this phone: their logins and whatever else they stored. */
fun forgetPages() {
    runCatching {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}
