package org.itmo.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Веб-страница с дашбордом на встроенном в JDK HTTP-сервере. Слушает только localhost и только отдаёт
 * метрики (GET): ни данных таблиц, ни записи через этот сервер нет.
 */
class DashboardServer(private val monitor: Monitor, port: Int) : AutoCloseable {
    private val page: ByteArray = checkNotNull(DashboardServer::class.java.getResourceAsStream("/dashboard.html")) {
        "В ресурсах нет dashboard.html"
    }.use { it.readBytes() }

    init {
        // Привязка к loopback удаётся, даже если порт уже слушает другой процесс на всех адресах,
        // и браузер потом ходит к нему. Поэтому занятость проверяем подключением.
        require(port == 0 || !isListening(port)) { "Порт $port уже занят другим процессом, укажите другой: port=<число>" }
    }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    val port: Int get() = server.address.port

    init {
        server.createContext("/", ::handle)
        server.executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "dashboard-http").apply { isDaemon = true } }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun isListening(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300) }
        true
    } catch (_: IOException) {
        false
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestMethod != "GET") return@use respond(exchange, 405, "text/plain; charset=utf-8", "Только GET".toByteArray())
            when (exchange.requestURI.path) {
                "/", "/index.html" -> respond(exchange, 200, "text/html; charset=utf-8", page)
                "/api/state" -> {
                    val since = exchange.requestURI.rawQuery?.split("&")
                        ?.firstOrNull { it.startsWith("since=") }?.removePrefix("since=")?.toLongOrNull() ?: 0L
                    respond(exchange, 200, "application/json; charset=utf-8", monitor.stateJson(since).toByteArray())
                }
                else -> respond(exchange, 404, "text/plain; charset=utf-8", "Не найдено".toByteArray())
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
