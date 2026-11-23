// Port of tile38 internal/endpoint (endpoint.go, local.go, http.go), Copyright Joshua J Baker, MIT License.
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Thrown for endpoint URLs that cannot be parsed or are not supported. */
class EndpointException(
    message: String,
) : Exception(message)

/**
 * Delivers hook messages to endpoints. Supports `local://channel` (pub/sub) and `http(s)://`.
 * Other Tile38 schemes (kafka, mqtt, grpc, ...) are recognized but not supported yet.
 */
class EndpointManager(
    private val publisher: (channel: String, message: String) -> Int,
) {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(
                java.util.concurrent.Executors
                    .newVirtualThreadPerTaskExecutor(),
            ).build()

    /** Validates an endpoint URL like upstream's `parseEndpoint`; throws [EndpointException]. */
    fun validate(url: String) {
        val scheme = parseScheme(url)
        if (scheme !in SUPPORTED) throw EndpointException("endpoint scheme '$scheme' is not supported yet")
    }

    /** Sends [msg] to [url]; throws on failure so the caller can retry. */
    fun send(
        url: String,
        msg: String,
    ) {
        when (parseScheme(url)) {
            "local" -> {
                publisher(localChannel(url), msg)
            }

            "http", "https" -> {
                val req =
                    HttpRequest
                        .newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(utf8(msg)))
                        .build()
                val res = http.send(req, HttpResponse.BodyHandlers.discarding())
                if (res.statusCode() != 200 && res.statusCode() != 201 && res.statusCode() != 202) {
                    throw EndpointException("invalid status: ${res.statusCode()}")
                }
            }

            else -> {
                throw EndpointException("invalid protocol")
            }
        }
    }

    companion object {
        private val SUPPORTED = setOf("local", "http", "https")
        private val KNOWN =
            listOf(
                "local:" to "local",
                "http:" to "http",
                "https:" to "https",
                "disque:" to "disque",
                "grpc:" to "grpc",
                "redis:" to "redis",
                "kafka:" to "kafka",
                "amqp:" to "amqp",
                "amqps:" to "amqp",
                "mqtt:" to "mqtt",
                "mqtts:" to "mqtt",
                "pubsub:" to "pubsub",
                "sqs:" to "sqs",
                "nats:" to "nats",
                "Endpoint=" to "eventhub",
                "cf-queue:" to "cfqueue",
            )

        /** Returns the scheme after the basic checks of upstream's `parseEndpoint`. */
        fun parseScheme(url: String): String {
            val scheme = KNOWN.firstOrNull { url.startsWith(it.first) }?.second ?: throw EndpointException("unknown scheme")
            val rest = url.substring(url.indexOf(':') + 1)
            if (!rest.startsWith("//")) throw EndpointException("missing the two slashes")
            val host = rest.substring(2).split("?")[0].split("/")[0]
            if (host == "") throw EndpointException(if (scheme == "local") "missing channel" else "missing host")
            return scheme
        }

        fun localChannel(url: String) = url.substring(url.indexOf(':') + 3).split("?")[0].split("/")[0]

        /** Splits endpoint URLs on commas, rejoining parts without a scheme (e.g. kafka brokers). */
        fun splitUrls(s: String): List<String> {
            val urls = ArrayList<String>()
            for (part0 in s.split(",")) {
                val part = part0.trim()
                if (urls.isNotEmpty() && !(part.contains("://") || part.startsWith("Endpoint="))) {
                    urls[urls.size - 1] = urls.last() + "," + part
                } else {
                    urls.add(part)
                }
            }
            return urls
        }

        private fun utf8(byteString: String) = String(byteString.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }
}
