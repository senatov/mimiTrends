package org.senatov.mimitrends.ws

import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.TradeTick
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class FinnhubWebSocketClient(
    private val apiKey: String,
    private val onTrade: Consumer<TradeTick>,
    private val onError: Consumer<Throwable>,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : WebSocket.Listener, AutoCloseable {
    private val log = LoggerFactory.getLogger(FinnhubWebSocketClient::class.java)
    private val mapper = ObjectMapper()
    private val subscriptions = CopyOnWriteArraySet<String>()
    private val messageBuffer = StringBuilder()
    private val closed = AtomicBoolean()
    @Volatile private var webSocket: WebSocket? = null

    fun connect(): CompletableFuture<WebSocket> {
        log.debug(LogTag.API, "connect()")
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("WebSocket client is closed"))
        val encodedToken = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        return httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://ws.finnhub.io?token=$encodedToken"), this)
            .thenApply { socket ->
                if (closed.get()) {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "MiMiTrends closed")
                    return@thenApply socket
                }
                webSocket = socket
                log.info(LogTag.API, "websocket connected")
                subscriptions.forEach(::sendSubscribe)
                socket
            }
    }

    fun subscribe(symbol: String) {
        val normalized = symbol.trim().uppercase()
        log.debug(LogTag.API, "subscribe(symbol={})", normalized)
        if (normalized.isEmpty() || closed.get()) return
        subscriptions += normalized
        if (webSocket != null) sendSubscribe(normalized)
    }

    fun unsubscribe(symbol: String) {
        val normalized = symbol.trim().uppercase()
        log.debug(LogTag.API, "unsubscribe(symbol={})", normalized)
        subscriptions -= normalized
        webSocket?.sendText(command("unsubscribe", normalized), true)
    }

    override fun onOpen(webSocket: WebSocket) {
        log.debug(LogTag.API, "onOpen()")
        if (closed.get()) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "MiMiTrends closed")
            return
        }
        this.webSocket = webSocket
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        log.debug(LogTag.API, "onText(chars={}, last={})", data.length, last)
        if (closed.get()) return null
        messageBuffer.append(data)
        if (last) {
            val message = messageBuffer.toString()
            messageBuffer.setLength(0)
            parseTrades(message).forEach(onTrade::accept)
        }
        webSocket.request(1)
        return null
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        log.debug(LogTag.API, "onBinary(bytes={}, last={})", data.remaining(), last)
        if (closed.get()) return null
        webSocket.request(1)
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        if (closed.get()) return
        log.error(LogTag.API, "websocket failed", error)
        onError.accept(error)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        log.info(LogTag.API, "websocket closed status={} reason={}", statusCode, reason)
        this.webSocket = null
        return null
    }

    internal fun parseTrades(json: String): List<TradeTick> {
        log.debug(LogTag.API, "parseTrades(chars={})", json.length)
        val root = mapper.readTree(json)
        if (root.path("type").asText() != "trade") return emptyList()
        return root.path("data").mapNotNull { item ->
            val symbol = item.path("s").asText()
            val price = item.path("p").asDouble(Double.NaN)
            if (symbol.isEmpty() || !price.isFinite()) return@mapNotNull null
            TradeTick(symbol, price, item.path("t").asLong(), item.path("v").asDouble())
        }
    }

    override fun close() {
        log.debug(LogTag.API, "close()")
        if (!closed.compareAndSet(false, true)) return
        webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "MiMiTrends closed")
        webSocket = null
    }

    private fun sendSubscribe(symbol: String) {
        log.debug(LogTag.API, "sendSubscribe(symbol={})", symbol)
        webSocket?.sendText(command("subscribe", symbol), true)
    }

    private fun command(type: String, symbol: String): String {
        log.debug(LogTag.API, "command(type={}, symbol={})", type, symbol)
        return mapper.writeValueAsString(mapOf("type" to type, "symbol" to symbol))
    }
}
