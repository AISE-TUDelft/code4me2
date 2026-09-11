package me.code4me.services.agent

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectTokenService
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal fun interface ManagedGrantIssuer {
    fun issue(project: Project, projectId: UUID, workspace: String, pathFormat: String, launchId: String): String
}

/** Loopback-only bridge that turns an authenticated IDE session into one short-lived ACP grant. */
internal class ManagedAuthBridge(
    private val discoveryDirectory: Path,
    private val issuer: ManagedGrantIssuer = ManagedGrantIssuer { project, projectId, workspace, pathFormat, launchId ->
        getAppService().prepareManagedAcpGrant(project, projectId, workspace, pathFormat, launchId)
    },
    private val backendUrlProvider: () -> String = { getAppService().getAcpRuntimeBaseUrl() },
    private val signedInProvider: () -> Boolean = { me.code4me.services.state.getAuthState().isAuthenticated() },
    private val serverFactory: () -> HttpServer = { HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0) },
) : AutoCloseable {
    private val log = thisLogger()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val capability = randomCapability()
    private val ideInstance = UUID.randomUUID().toString()
    private val projects = ConcurrentHashMap<String, Project>()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val discoveryPath: Path get() = discoveryDirectory.resolve("$ideInstance.json")
    val baseUrl: String get() = server?.address?.port?.let { "http://127.0.0.1:$it" }.orEmpty()

    @Synchronized fun start() {
        if (server != null) return
        val created = serverFactory()
        created.createContext("/v1/grant", ::handleGrant)
        created.createContext("/v1/status", ::handleStatus)
        val createdExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "code4me-auth-bridge").apply { isDaemon = true }
        }
        created.executor = createdExecutor
        try {
            created.start()
            server = created
            executor = createdExecutor
            writeDiscovery()
        } catch (e: Exception) {
            created.stop(0)
            createdExecutor.shutdownNow()
            server = null
            executor = null
            throw e
        }
    }

    fun register(project: Project) {
        val workspace = canonicalWorkspace(project) ?: return
        projects[workspace] = project
        if (server == null) start() else writeDiscovery()
    }

    fun unregister(project: Project) {
        projects.entries.removeIf { it.value === project }
        if (server != null) writeDiscovery()
    }

    @Synchronized
    private fun handleGrant(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
            if (!hasCapability(exchange)) {
                return respond(exchange, 401, "{\"error\":\"invalid_capability\"}")
            }
            if (!runCatching(signedInProvider).getOrDefault(false)) {
                return respond(exchange, 401, "{\"error\":\"signed_out\"}")
            }
            val rawBody = exchange.requestBody.readNBytes(MAX_REQUEST_BYTES + 1)
            if (rawBody.size > MAX_REQUEST_BYTES) {
                return respond(exchange, 413, "{\"error\":\"request_too_large\"}")
            }
            val body = json.parseToJsonElement(rawBody.toString(StandardCharsets.UTF_8)).jsonObject
            if (body["managed_protocol_version"]?.jsonPrimitive?.content != "1") {
                return respond(exchange, 400, "{\"error\":\"unsupported_protocol\"}")
            }
            val requestedWorkspace = normalizeRequestedWorkspace(body["workspace"]?.jsonPrimitive?.content.orEmpty())
                ?: return respond(exchange, 400, "{\"error\":\"invalid_workspace\"}")
            val project = projects[requestedWorkspace]
                ?: return respond(exchange, 403, "{\"error\":\"workspace_not_open\"}")
            val projectId = getProjectTokenService(project).getProjectToken()?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            } ?: return respond(exchange, 409, "{\"error\":\"project_not_active\"}")
            val pathFormat = body["path_format"]?.jsonPrimitive?.content.orEmpty()
            if (pathFormat != pathFormat()) return respond(exchange, 400, "{\"error\":\"path_format_mismatch\"}")
            val launchId = body["launch_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return respond(exchange, 400, "{\"error\":\"launch_id_required\"}")
            val backendResponse = issuer.issue(project, projectId, requestedWorkspace, pathFormat, launchId)
            val result = json.parseToJsonElement(backendResponse).jsonObject.toMutableMap().also {
                it["backend_url"] = JsonPrimitive(backendUrlProvider())
            }
            respond(exchange, 200, JsonObject(result).toString())
        } catch (e: Exception) {
            log.warn("Managed grant bridge request failed", e)
            respond(exchange, 502, "{\"error\":\"grant_unavailable\"}")
        }
    }

    private fun handleStatus(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        if (!hasCapability(exchange)) return respond(exchange, 401, "{\"error\":\"invalid_capability\"}")
        respond(exchange, 200, "{\"status\":\"ok\",\"protocol_version\":\"1\"}")
    }

    private fun hasCapability(exchange: HttpExchange): Boolean {
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        val supplied = authorization?.takeIf { it.startsWith("Bearer ") }?.substring(7)
            ?: exchange.requestHeaders.getFirst("X-Code4Me-Capability")
        return capabilitiesEqual(supplied, capability)
    }

    @Synchronized private fun writeDiscovery() {
        Files.createDirectories(discoveryDirectory)
        restrictOwnerOnly(discoveryDirectory, directory = true)
        val workspaces = projects.keys.sorted().map { workspace ->
            JsonObject(mapOf("workspace" to JsonPrimitive(workspace), "path_format" to JsonPrimitive(pathFormat())))
        }
        val payload = buildJsonObject {
            put("protocol_version", "1")
            put("ide_instance", ideInstance)
            put("base_url", baseUrl)
            put("capability", capability)
            put("workspaces", JsonArray(workspaces))
        }.toString()
        val temp = Files.createTempFile(discoveryDirectory, ".bridge-", ".tmp")
        try {
            Files.writeString(temp, payload, StandardCharsets.UTF_8)
            restrictOwnerOnly(temp)
            try { Files.move(temp, discoveryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: Exception) { Files.move(temp, discoveryPath, StandardCopyOption.REPLACE_EXISTING) }
            restrictOwnerOnly(discoveryPath)
        } finally { Files.deleteIfExists(temp) }
    }

    @Synchronized override fun close() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        Files.deleteIfExists(discoveryPath)
        projects.clear()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun normalizeRequestedWorkspace(value: String): String? = try {
        Path.of(value).toRealPath().toString().replace('\\', '/')
    } catch (_: Exception) { null }

    private fun canonicalWorkspace(project: Project): String? = project.basePath?.let(::normalizeRequestedWorkspace)

    private fun restrictOwnerOnly(path: Path, directory: Boolean = false) {
        val aclView = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        if (aclView != null) {
            val owner = Files.getOwner(path)
            val ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(AclEntryPermission.entries.toSet())
                .build()
            aclView.acl = listOf(ownerEntry)
            return
        }
        try {
            val permissions = mutableSetOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            if (directory) permissions += PosixFilePermission.OWNER_EXECUTE
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            val file = path.toFile()
            check(file.setReadable(false, false) && file.setWritable(false, false)) {
                "Could not remove shared permissions from $path"
            }
            check(file.setReadable(true, true) && file.setWritable(true, true)) {
                "Could not grant owner permissions to $path"
            }
            if (directory) {
                check(file.setExecutable(true, true)) { "Could not grant owner directory access to $path" }
            }
        }
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 64 * 1024

        fun pathFormat(): String = if (System.getProperty("os.name").startsWith("Windows", true)) "windows" else "posix"
        private fun capabilitiesEqual(supplied: String?, expected: String): Boolean {
            if (supplied == null) return false
            return MessageDigest.isEqual(
                supplied.toByteArray(StandardCharsets.UTF_8),
                expected.toByteArray(StandardCharsets.UTF_8),
            )
        }

        private fun randomCapability(): String {
            val bytes = ByteArray(32); SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
