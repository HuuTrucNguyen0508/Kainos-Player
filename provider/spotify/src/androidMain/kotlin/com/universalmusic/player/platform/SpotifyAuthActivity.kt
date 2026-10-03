package com.universalmusic.player.platform

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.net.URI

class SpotifyAuthActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL)
        val redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI)
        if (authUrl.isNullOrBlank() || redirectUri.isNullOrBlank()) {
            complete(null)
            finish()
            return
        }
        val redirectPrefix = redirectUriPrefix(redirectUri)
        val webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = handleRedirect(request?.url?.toString(), redirectPrefix)

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    handleRedirect(url, redirectPrefix)

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    view?.setBackgroundColor(Color.WHITE)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(
                            this@SpotifyAuthActivity,
                            "Spotify login failed to load: ${error?.description ?: "unknown error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            loadUrl(authUrl)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(
                    webView,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            },
        )
    }

    override fun onDestroy() {
        complete(null)
        super.onDestroy()
    }

    private fun handleRedirect(url: String?, redirectPrefix: String): Boolean {
        if (url == null || !url.startsWith(redirectPrefix)) return false
        complete(url)
        finish()
        return true
    }

    private fun complete(result: String?) {
        SpotifyAuthRelay.pending?.complete(result)
        SpotifyAuthRelay.pending = null
    }

    companion object {
        const val EXTRA_AUTH_URL = "auth_url"
        const val EXTRA_REDIRECT_URI = "redirect_uri"

        private fun redirectUriPrefix(redirectUri: String): String {
            val redirect = URI(redirectUri)
            val path = redirect.rawPath.takeUnless { it.isNullOrBlank() } ?: "/"
            return buildString {
                append(redirect.scheme)
                append("://")
                append(redirect.host)
                if (redirect.port > 0) {
                    append(':')
                    append(redirect.port)
                }
                append(path)
            }
        }
    }
}
