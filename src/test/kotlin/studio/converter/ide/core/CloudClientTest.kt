package studio.converter.ide.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.Collections

/** The API client against a small fake Converter Studio on 127.0.0.1 (the JDK's own HTTP server). */
class CloudClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = Collections.synchronizedList(ArrayList<Recorded>())
    private val routes = LinkedHashMap<String, (HttpExchange, ByteArray) -> Unit>()

    data class Recorded(val method: String, val path: String, val headers: Map<String, String?>, val body: ByteArray)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes()
            val headers = listOf("Authorization", "X-Converter-Studio-Client", "Idempotency-Key", "Upload-Offset", "User-Agent", "Content-Type")
                .associateWith { exchange.requestHeaders.getFirst(it) }
            val path = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            requests += Recorded(exchange.requestMethod, path, headers, body)
            val handler = routes.entries.firstOrNull { (key, _) -> "${exchange.requestMethod} ${exchange.requestURI.rawPath}".startsWith(key) }?.value
            if (handler == null) reply(exchange, 404, """{"error":{"code":"notFound","message":"No such thing."}}""") else handler(exchange, body)
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/api"
    }

    @After
    fun stop() = server.stop(0)

    private fun reply(exchange: HttpExchange, status: Int, json: String, headers: Map<String, String> = emptyMap()) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun client(key: String? = "cvs_live_secret", retries: Int = 3) =
        CloudClient(base, key, "1.2.3", retries = retries, pause = { _, _ -> })

    private fun view(id: String, status: String, extra: String = "") =
        """{"id":"$id","status":"$status","from":"png","to":"webp","credits":1,"maxCredits":20$extra}"""

    @Test
    fun fullConversionSendsTheKeyOnlyToTheApi() {
        val file = temp.newFile("photo.png").toPath()
        Files.write(file, ByteArray(1000) { it.toByte() })
        routes["POST /api/v1/uploads"] = { ex, _ -> reply(ex, 201, """{"id":"up1","uploadUrl":"$base/v1/uploads/up1?token=t1","maxBytes":1000000,"partBytes":400}""") }
        var received = 0
        routes["PUT /api/v1/uploads/up1"] = { ex, body ->
            received += body.size
            reply(ex, 200, if (received < 1000) """{"id":"up1","status":"partial","received":$received}""" else """{"id":"up1","status":"ready","file":{"name":"photo.png","size":1000,"format":"png"}}""")
        }
        routes["POST /api/v1/conversions"] = { ex, _ -> reply(ex, 201, view("up1", "queued")) }
        routes["GET /api/v1/conversions/up1"] = { ex, _ -> reply(ex, 200, view("up1", "done", ""","outputName":"photo.webp","downloadUrl":"$base/jobs/up1/download?token=d1"""")) }
        routes["GET /api/jobs/up1/download"] = { ex, _ ->
            ex.sendResponseHeaders(200, 4)
            ex.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        val c = client()
        val uploadId = c.upload(file, "photo.png", CancelCheck.NEVER)
        assertEquals("up1", uploadId)
        val started = c.start(uploadId, "webp", emptyMap(), 20, "idem-1", CancelCheck.NEVER)
        val done = c.settle(started, Duration.ofMinutes(1), CancelCheck.NEVER)
        assertEquals("done", done.status)
        assertEquals("photo.webp", done.outputName)
        val out = temp.root.toPath().resolve("result")
        c.download(done.downloadUrl!!, out, CancelCheck.NEVER)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(out))

        // Three parts of at most 400 bytes, with their offsets, without the key.
        val puts = requests.filter { it.method == "PUT" }
        assertEquals(listOf("0", "400", "800"), puts.map { it.headers["Upload-Offset"] })
        assertEquals(listOf(400, 400, 200), puts.map { it.body.size })
        for (r in requests) {
            assertEquals("jetbrains/1.2.3", r.headers["X-Converter-Studio-Client"])
            assertEquals("jetbrains-converter-studio/1.2.3", r.headers["User-Agent"])
            val isApi = r.path.startsWith("/api/v1/") && r.method != "PUT"
            assertEquals(r.path, if (isApi) "Bearer cvs_live_secret" else null, r.headers["Authorization"])
        }
        val post = requests.first { it.method == "POST" && it.path == "/api/v1/conversions" }
        assertEquals("idem-1", post.headers["Idempotency-Key"])
        assertEquals(mapOf("uploadId" to "up1", "to" to "webp", "maxCredits" to 20L), Json.parse(String(post.body)))
        assertTrue(requests.any { it.path == "/api/v1/conversions/up1?wait=50" })
    }

    @Test
    fun anUploadLinkElsewhereIsRefused() {
        val file = temp.newFile("a.png").toPath()
        Files.write(file, ByteArray(10))
        routes["POST /api/v1/uploads"] = { ex, _ -> reply(ex, 201, """{"id":"u","uploadUrl":"https://evil.example/u?token=x","partBytes":100}""") }
        val e = assertThrows(CloudException::class.java) { client().upload(file, "a.png", CancelCheck.NEVER) }
        assertEquals("badResponse", e.code)
        assertFalse(requests.any { it.method == "PUT" })
    }

    @Test
    fun theApisErrorMessageReachesTheUser() {
        routes["GET /api/v1/account"] = { ex, _ -> reply(ex, 401, """{"error":{"code":"invalidApiKey","message":"This API key is not valid. Create a new one on your account page."}}""") }
        val e = assertThrows(CloudException::class.java) { client(key = "cvs_live_wrong").account() }
        assertEquals("invalidApiKey", e.code)
        assertEquals("This API key is not valid. Create a new one on your account page.", e.message)
        assertEquals(401, e.status)
        assertFalse(e.message!!.contains("cvs_live_wrong"))
    }

    @Test
    fun busyServersAreTriedAgain() {
        var calls = 0
        routes["GET /api/v1/formats"] = { ex, _ ->
            calls++
            if (calls < 3) reply(ex, 503, """{"error":{"code":"serverBusy","message":"Busy."}}""", mapOf("Retry-After" to "1"))
            else reply(ex, 200, """{"formats":[{"id":"png","name":"PNG","category":"image","extensions":["png"],"to":["jpg"]}]}""")
        }
        val catalogue = client(key = null).formats()
        assertEquals(3, calls)
        assertEquals(listOf("jpg"), catalogue.cloudTargets("png"))
        // The formats need no key, and none is sent.
        assertTrue(requests.all { it.headers["Authorization"] == null })
    }

    @Test
    fun aQuotedConversionWaitsForConfirm() {
        routes["GET /api/v1/conversions/c1"] = { ex, _ -> reply(ex, 200, view("c1", "quoted", ""","credits":42""")) }
        routes["POST /api/v1/conversions/c1/confirm"] = { ex, _ -> reply(ex, 200, view("c1", "queued")) }
        val c = client()
        val quoted = c.settle(ConversionView("c1", "measuring"), Duration.ofMinutes(1), CancelCheck.NEVER)
        assertEquals("quoted", quoted.status)
        assertEquals("queued", c.confirm("c1", CancelCheck.NEVER).status)
    }

    @Test
    fun cancelStopsAWaitingRequest() {
        routes["GET /api/v1/conversions/slow"] = { ex, _ ->
            Thread.sleep(3000)
            reply(ex, 200, view("slow", "processing"))
        }
        val started = System.nanoTime()
        val deadline = started + 300_000_000
        assertThrows(CanceledException::class.java) { client().get("slow", 50, CancelCheck { System.nanoTime() > deadline }) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2500)
    }

    @Test
    fun onlyHttpsOrThisComputer() {
        assertEquals("https://converter.studio/api", Endpoints.checkApiUrl("https://converter.studio/api/"))
        assertEquals("https://converter.studio/api", Endpoints.checkApiUrl(" "))
        assertEquals("http://localhost:9521/api", Endpoints.checkApiUrl("http://localhost:9521/api"))
        for (bad in listOf("http://converter.studio/api", "ftp://x/api", "https://user:pw@converter.studio/api", "https://converter.studio/api?x=1", "converter.studio")) {
            assertThrows(bad, ConversionException::class.java) { Endpoints.checkApiUrl(bad) }
        }
        assertThrows(ConversionException::class.java) { CloudClient("http://example.com/api", "k", "1") }
    }

    @Test
    fun noKeyNoRequest() {
        val e = assertThrows(ConversionException::class.java) { client(key = null).account() }
        assertEquals("noApiKey", e.code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun conversionViewAndNames() {
        val v = ConversionView.parse(Json.parse(view("x", "failed", ""","error":{"code":"conversionFailed","message":"The file is damaged."}""")).asObject()!!)
        assertEquals("conversionFailed", v.errorCode)
        assertEquals("The file is damaged.", v.errorMessage)
        assertTrue(v.settled)
        assertNull(v.downloadUrl)
        assertEquals("a.png", CloudClient.uploadName(" a\u0000.png "))
        assertEquals(255, CloudClient.uploadName("x".repeat(300) + ".png").length)
        assertTrue(CloudClient.uploadName("x".repeat(300) + ".png").endsWith(".png"))
    }
}
