package com.universalmusic.player

import android.content.Context
import android.content.Intent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.universalmusic.player.platform.SpotifyAuthActivity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live requests: the policy is what the network stack and WebView enforce, not the XML. */
@RunWith(AndroidJUnit4::class)
class AndroidCleartextPolicyTest {
    @Test fun loopbackPlainHttpLoads() = withPage("<html>ok</html>") { port, _ ->
        val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        assertEquals(200, connection.responseCode)
    }

    @Test fun nonLoopbackPlainHttpIsBlockedBeforeAnySocketOpens() {
        // TEST-NET-1 and an unrouted name: only the policy can produce this message.
        for (url in listOf("http://192.0.2.1/", "http://example.invalid/")) {
            val failure = assertThrows(IOException::class.java) {
                (URL(url).openConnection() as HttpURLConnection).responseCode
            }
            assertTrue("$url: $failure", failure.message.orEmpty().contains("not permitted"))
        }
    }

    @Test fun webViewBlocksNonLoopbackPlainHttp() {
        val failure = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)
        lateinit var webView: WebView
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            webView = WebView(ApplicationProvider.getApplicationContext<Context>()).apply {
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        failure[0] = error.description.toString()
                        done.countDown()
                    }
                }
                loadUrl("http://192.0.2.1/")
            }
        }
        assertTrue("WebView reported no error", done.await(20, TimeUnit.SECONDS))
        assertEquals("net::ERR_CLEARTEXT_NOT_PERMITTED", failure[0])
        instrumentation.runOnMainSync { webView.destroy() }
    }

    @Test fun spotifyLoginPageLoadsOnLoopbackAndItsRedirectCompletes() {
        // Same redirect the librespot login uses, so this is the page and hop a real login takes.
        val page = "<html><script>location.href='http://127.0.0.1:5588/login?code=proof'</script></html>"
        withPage(page) { port, requests ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val intent = Intent(context, SpotifyAuthActivity::class.java).apply {
                putExtra(SpotifyAuthActivity.EXTRA_AUTH_URL, "http://127.0.0.1:$port/authorize")
                putExtra(SpotifyAuthActivity.EXTRA_REDIRECT_URI, "http://127.0.0.1:5588/login")
            }
            ActivityScenario.launch<SpotifyAuthActivity>(intent).use { scenario ->
                val deadline = System.currentTimeMillis() + 20_000
                while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
            assertTrue("Page was not requested: $requests", requests.any { it.startsWith("GET /authorize") })
        }
    }

    private fun withPage(body: String, block: (port: Int, requests: List<String>) -> Unit) {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serving = thread(isDaemon = true) {
            runCatching {
                while (true) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()?.let(requests::add)
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val payload = body.encodeToByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        write(payload)
                        flush()
                    }
                }
            }
        }
        try {
            block(server.localPort, requests)
        } finally {
            server.close()
            serving.join(2_000)
        }
    }
}
