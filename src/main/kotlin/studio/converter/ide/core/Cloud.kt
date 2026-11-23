package studio.converter.ide.core

import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A conversion as the API shows it (`GET /v1/conversions/:id`). */
data class ConversionView(
    val id: String,
    val status: String,
    val from: String? = null,
    val to: String? = null,
    val credits: Long? = null,
    val maxCredits: Long? = null,
    val progress: Double? = null,
    val outputName: String? = null,
    val outputSize: Long? = null,
    val downloadUrl: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val settled: Boolean get() = status in SETTLED

    companion object {
        val SETTLED = setOf("done", "failed", "expired", "canceled", "quoted")

        fun parse(o: Map<String, Any?>): ConversionView {
            val error = o["error"].asObject()
            return ConversionView(
                id = o.string("id") ?: throw CloudException("badResponse", "Converter Studio sent a conversion without an id."),
                status = o.string("status") ?: "",
                from = o.string("from"),
                to = o.string("to"),
                credits = o.long("credits"),
                maxCredits = o.long("maxCredits"),
                progress = o.double("progress"),
                outputName = o.string("outputName"),
                outputSize = o.long("outputSize"),
                downloadUrl = o.string("downloadUrl"),
                errorCode = error?.string("code"),
                errorMessage = error?.string("message"),
            )
        }
    }
}

/** The account behind the API key (`GET /v1/account`), without its e-mail address. */
data class AccountView(val plan: String, val credits: Long, val maxFileBytes: Long?)

/** An error from Converter Studio's API (its `code` and `message`), or from talking to it. */
class CloudException(code: String, message: String, val status: Int? = null, val retryAfter: Long? = null, cause: Throwable? = null) :
    ConversionException(code, message, cause)

/** The API's address: HTTPS only (plain HTTP only for a test server on this computer). */
object Endpoints {
    const val DEFAULT_API_URL = "https://converter.studio/api"

    /** The address without a trailing slash, or a [ConversionException] saying what is wrong. */
    fun checkApiUrl(text: String): String {
        val trimmed = text.trim().ifEmpty { DEFAULT_API_URL }.trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        val host = uri?.host?.lowercase(Locale.ROOT)
        val bad = when {
            uri == null || host.isNullOrEmpty() -> "is not a web address"
            uri.rawUserInfo != null -> "must not contain a user name or password"
            uri.rawQuery != null || uri.rawFragment != null -> "must not contain ? or #"
            scheme == "https" -> null
            scheme == "http" && isLoopback(host) -> null
            else -> "must start with https://"
        }
        if (bad != null) throw ConversionException("badApiUrl", "The API address \"$trimmed\" $bad.")
        return trimmed
    }

    fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"

    fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)

    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
}

/**
 * A small client for Converter Studio's public REST API (https://converter.studio/developers):
 * the formats, the account, uploads (in parts when large), conversions and downloads.
 *
 * The API key is sent only with requests to the configured API address, over HTTPS, and never
 * written to a log or an error message. Uploads and downloads use the one-time links the API
 * returns (each carries its own token): an upload link must be on the API's own address.
 */
class CloudClient(
    apiUrl: String,
    private val apiKey: String?,
    private val version: String,
    private val http: HttpClient = defaultHttpClient(),
    private val retries: Int = 3,
    private val pause: (Long, CancelCheck) -> Unit = ::sleep,
) {
    val apiUrl: String = Endpoints.checkApiUrl(apiUrl)
    private val origin = URI(this.apiUrl)
    private val userAgent = "jetbrains-converter-studio/$version"

    /** The formats Converter Studio reads and what each can become (no key needed). */
    fun formats(cancel: CancelCheck = CancelCheck.NEVER): Catalogue = try {
        Catalogue.fromApi(text(api("GET", "/v1/formats", auth = false), cancel))
    } catch (e: JsonException) {
        throw CloudException("badResponse", "Converter Studio sent a formats list the plugin cannot read.", cause = e)
    }

    /** The plan and credits of the key's account. */
    fun account(cancel: CancelCheck = CancelCheck.NEVER): AccountView {
        val o = obj(api("GET", "/v1/account"), cancel)
        return AccountView(o.string("plan") ?: "", o.long("credits") ?: 0, o.long("maxFileBytes"))
    }

    /**
     * Uploads a file: an upload slot first (`POST /v1/uploads`, which checks the plan's size limit
     * before anything is sent), then the bytes in one request, or in parts with `Upload-Offset`.
     * Returns the upload's id.
     */
    fun upload(file: Path, fileName: String, cancel: CancelCheck, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): String {
        val size = Files.size(file)
        if (size <= 0) throw ConversionException("sourceEmpty", "The file is empty.")
        val slot = obj(api("POST", "/v1/uploads", mapOf("fileName" to uploadName(fileName), "size" to size)), cancel)
        val id = slot.string("id") ?: throw CloudException("badResponse", "Converter Studio sent an upload slot without an id.")
        val uploadUrl = slot.string("uploadUrl")?.let { runCatching { URI(it) }.getOrNull() }
        if (uploadUrl == null || !Endpoints.sameOrigin(uploadUrl, origin)) {
            throw CloudException("badResponse", "Converter Studio sent an upload link outside its own address; nothing was sent.")
        }
        val partBytes = slot.long("partBytes")?.takeIf { it > 0 } ?: DEFAULT_PART_BYTES
        val inParts = size > partBytes
        var offset = 0L
        while (offset < size) {
            val length = minOf(partBytes, size - offset)
            val start = offset
            val receipt = obj(
                {
                    val builder = HttpRequest.newBuilder(uploadUrl)
                        .timeout(Duration.ofMillis(120_000 + (length / 1_000_000 + 1) * 2000))
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream { region(file, start, length) { onProgress(start + it, size) } }, length))
                    if (inParts) builder.header("Upload-Offset", start.toString())
                    common(builder).build()
                },
                cancel,
            )
            offset += length
            onProgress(offset, size)
            when (receipt.string("status")) {
                "ready" -> if (offset == size) return id else break
                "partial" -> if (receipt.long("received") != offset) break
                else -> break
            }
        }
        throw CloudException("uploadIncomplete", "The upload to Converter Studio did not arrive whole.")
    }

    /**
     * Starts a conversion of an upload. The Idempotency-Key makes a request that is sent again
     * after a lost answer return the same conversion instead of a second one.
     */
    fun start(uploadId: String, to: String, options: Map<String, Any?>, maxCredits: Long, idempotencyKey: String, cancel: CancelCheck): ConversionView {
        val body = LinkedHashMap<String, Any?>()
        body["uploadId"] = uploadId
        body["to"] = to
        if (options.isNotEmpty()) body["options"] = options
        body["maxCredits"] = maxCredits
        return ConversionView.parse(obj(api("POST", "/v1/conversions", body, headers = mapOf("Idempotency-Key" to idempotencyKey)), cancel))
    }

    /** A conversion's status; with [waitSeconds], the server waits until it changes. */
    fun get(id: String, waitSeconds: Int, cancel: CancelCheck): ConversionView {
        val query = if (waitSeconds > 0) "?wait=${waitSeconds.coerceAtMost(60)}" else ""
        return ConversionView.parse(obj(api("GET", "/v1/conversions/${encodePath(id)}$query", timeout = Duration.ofSeconds(waitSeconds + 30L)), cancel))
    }

    /** Goes ahead with a conversion that costs more than its `maxCredits` (status `quoted`). */
    fun confirm(id: String, cancel: CancelCheck): ConversionView =
        ConversionView.parse(obj(api("POST", "/v1/conversions/${encodePath(id)}/confirm", emptyMap<String, Any?>()), cancel))

    /** Waits until the conversion is done, failed, expired, canceled or quoted. */
    fun settle(view: ConversionView, timeout: Duration, cancel: CancelCheck, onUpdate: (ConversionView) -> Unit = {}): ConversionView {
        val deadline = System.nanoTime() + timeout.toNanos()
        var current = view
        while (!current.settled) {
            val left = (deadline - System.nanoTime()) / 1_000_000_000
            if (left <= 0) {
                throw CloudException("timeout", "The conversion did not finish in ${timeout.toMinutes()} minutes. It goes on at Converter Studio as ${current.id}.")
            }
            current = get(current.id, left.toInt().coerceIn(1, LONG_POLL_SECONDS), cancel)
            onUpdate(current)
        }
        return current
    }

    /** Saves a finished conversion's file to [destination]. The link carries its own token; no key is sent. */
    fun download(downloadUrl: String, destination: Path, cancel: CancelCheck, onProgress: (Long) -> Unit = {}) {
        val uri = runCatching { URI(downloadUrl) }.getOrNull()
        val safe = uri != null && uri.host != null && (uri.scheme.equals("https", true) || Endpoints.sameOrigin(uri, origin))
        if (!safe) throw CloudException("badResponse", "Converter Studio sent a download link that is not HTTPS.")
        val response = send({ common(HttpRequest.newBuilder(uri).timeout(Duration.ofHours(1)).GET()).build() }, cancel)
        response.body().use { body ->
            Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    cancel.check()
                    val n = body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    onProgress(total)
                }
            }
        }
    }

    // ---- requests ----

    /** A request to the API's own address; only these carry the key. */
    private fun api(
        method: String,
        path: String,
        body: Any? = null,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(60),
    ): () -> HttpRequest {
        if (auth && apiKey.isNullOrBlank()) throw ConversionException("noApiKey", "No Converter Studio API key is set. Add one in Settings | Tools | Converter Studio.")
        val uri = URI(apiUrl + path)
        return {
            val builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
            if (auth) builder.header("Authorization", "Bearer $apiKey")
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            }
            common(builder).build()
        }
    }

    private fun common(builder: HttpRequest.Builder): HttpRequest.Builder = builder
        .header("User-Agent", userAgent)
        // Names this plugin on its requests (Converter Studio's statistics of its connectors).
        .header("X-Converter-Studio-Client", "jetbrains/$version")

    private fun text(build: () -> HttpRequest, cancel: CancelCheck): String =
        send(build, cancel).body().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }

    private fun obj(build: () -> HttpRequest, cancel: CancelCheck): Map<String, Any?> {
        val text = text(build, cancel)
        return try {
            Json.parse(text).asObject()
        } catch (e: JsonException) {
            null
        } ?: throw CloudException("badResponse", "Converter Studio sent an answer the plugin cannot read.")
    }

    /** Sends a request, trying again while the API is busy or unreachable. */
    private fun send(build: () -> HttpRequest, cancel: CancelCheck): HttpResponse<InputStream> {
        var attempt = 0
        while (true) {
            cancel.check()
            val response = try {
                await(http.sendAsync(build(), HttpResponse.BodyHandlers.ofInputStream()), cancel)
            } catch (e: IOException) {
                if (attempt < retries) {
                    pause(1000L shl attempt, cancel)
                    attempt++
                    continue
                }
                throw CloudException("network", "Converter Studio could not be reached. Check the connection and try again.", cause = e)
            }
            if (response.statusCode() in 200..299) return response
            val error = readError(response)
            // Uploads waiting or left unconverted are counted over hours: trying again soon cannot help.
            val lasting = error.code == "tooManyPending" || error.code == "tooManyUnconverted"
            if (response.statusCode() in RETRY_STATUS && attempt < retries && !lasting) {
                pause(minOf(error.retryAfter?.times(1000) ?: (1000L shl attempt), 30_000), cancel)
                attempt++
                continue
            }
            throw error
        }
    }

    private fun <T> await(future: CompletableFuture<T>, cancel: CancelCheck): T {
        while (true) {
            try {
                return future.get(200, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (cancel.isCanceled()) {
                    future.cancel(true)
                    throw CanceledException()
                }
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw if (cause is IOException) cause else IOException(cause?.message ?: "request failed", cause)
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw CanceledException()
            }
        }
    }

    /** The API's error (`{ "error": { "code", "message" } }`), or one made from the status. */
    private fun readError(response: HttpResponse<InputStream>): CloudException {
        val status = response.statusCode()
        val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.toLongOrNull()
        val text = runCatching { response.body().use { String(it.readNBytes(64 * 1024), StandardCharsets.UTF_8) } }.getOrDefault("")
        val error = runCatching { Json.parse(text).asObject()?.get("error").asObject() }.getOrNull()
        val code = error?.string("code") ?: when {
            status == 401 -> "invalidApiKey"
            status == 402 -> "notEnoughCredits"
            status == 404 -> "notFound"
            status == 413 -> "fileTooLarge"
            status == 429 -> "tooManyRequests"
            status >= 500 -> "serverBusy"
            else -> "badResponse"
        }
        val message = error?.string("message") ?: "Converter Studio answered $status."
        return CloudException(code, message, status, retryAfter)
    }

    companion object {
        /** One upload request carries at most this much when the API does not say (the CDN stops at 100 MB). */
        const val DEFAULT_PART_BYTES = 95L * 1024 * 1024

        /** The longest a single status request waits on the server (the API allows 60 seconds). */
        const val LONG_POLL_SECONDS = 50
        private const val MAX_NAME_LENGTH = 255
        private val RETRY_STATUS = setOf(408, 429, 500, 502, 503, 504)

        fun defaultHttpClient(): HttpClient {
            val builder = HttpClient.newBuilder()
                // HTTP/1.1: no "Upgrade: h2c" on a plain-HTTP test server (some servers drop it).
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                // A redirect would carry the request (and the key) elsewhere: never followed.
                .followRedirects(HttpClient.Redirect.NEVER)
            // The IDE's proxy settings, when it has set a proxy selector.
            ProxySelector.getDefault()?.let { builder.proxy(it) }
            return builder.build()
        }

        /** A file name the API takes: no control characters, at most 255 characters, keeping the extension. */
        fun uploadName(name: String): String {
            val trimmed = name.replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim().ifEmpty { "file" }
            if (trimmed.length <= MAX_NAME_LENGTH) return trimmed
            val dot = trimmed.lastIndexOf('.')
            val extension = if (dot > 0 && trimmed.length - dot <= 16) trimmed.substring(dot) else ""
            return trimmed.substring(0, MAX_NAME_LENGTH - extension.length) + extension
        }

        private fun encodePath(id: String): String = java.net.URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")

        private fun sleep(ms: Long, cancel: CancelCheck) {
            val end = System.nanoTime() + ms * 1_000_000
            while (System.nanoTime() < end) {
                cancel.check()
                Thread.sleep(minOf(100L, (end - System.nanoTime()) / 1_000_000 + 1))
            }
        }

        /** [length] bytes of a file from [offset], counting what has been read. */
        private fun region(file: Path, offset: Long, length: Long, onRead: (Long) -> Unit): InputStream {
            val channel = FileChannel.open(file, StandardOpenOption.READ).position(offset)
            val stream = Channels.newInputStream(channel)
            return object : InputStream() {
                var left = length
                var read = 0L

                override fun read(): Int {
                    if (left <= 0) return -1
                    val b = stream.read()
                    if (b >= 0) count(1)
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = stream.read(b, off, minOf(len.toLong(), left).toInt())
                    if (n > 0) count(n.toLong())
                    return n
                }

                private fun count(n: Long) {
                    left -= n
                    read += n
                    onRead(read)
                }

                override fun close() = stream.close()
            }
        }
    }
}
