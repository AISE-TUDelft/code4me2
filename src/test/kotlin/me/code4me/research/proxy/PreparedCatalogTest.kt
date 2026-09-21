package me.code4me.research.proxy

import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.sha256Hex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PreparedCatalogTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun verifiesServerExecutionInventoryAndRejectsTampering() {
        val prefix = "agents/release/macos-aarch64/"
        val payloads = linkedMapOf("agent" to "agent-binary", "library-λ" to "library")
        val files = payloads.map { (name, body) ->
            val target = root.resolve(prefix + name)
            Files.createDirectories(target.parent)
            Files.writeString(target, body)
            mapOf("path" to name, "sha256" to sha256Hex(body),
                "size" to body.toByteArray().size, "executable" to (name == "agent"))
        }
        val execution = mapOf("schema_version" to "1",
            "entrypoint" to listOf("agent", "--managed"), "files" to files)
        // Pinned by the server's PackagedExecution test, including UTF-8 filenames.
        val executionDigest = "ce0745a4448a63f335382cb878a015033bc4f916e5170e4604497be6e7c82bc5"
        assertEquals(executionDigest, sha256Hex(canonicalJson(execution)))
        Files.writeString(root.resolve("proxy"), "proxy")
        val agent = mutableMapOf<String, Any?>(
            "release_id" to "release", "artifact_digest" to "a".repeat(64),
            "digest" to sha256Hex("agent-binary"), "adapter_digest" to "b".repeat(64),
            "execution_manifest_digest" to "sha256:$executionDigest", "execution" to execution,
            "entrypoint" to listOf(prefix + "agent", "--managed"),
            "files" to files.map { it + ("path" to prefix + it["path"]) },
        )
        val platform = mapOf(
            "os" to "macos", "arch" to "aarch64", "self_contained" to true,
            "entrypoint" to listOf("proxy"),
            "files" to listOf(mapOf("path" to "proxy", "sha256" to sha256Hex("proxy"),
                "size" to 5, "executable" to true)),
            "agents" to listOf(agent),
        )
        fun writeManifest() = Files.writeString(root.resolve("proxy-manifest.json"),
            canonicalJson(mapOf("schema_version" to "1", "platforms" to listOf(platform))))
        writeManifest()
        val resolver = PackagedProxyRuntimeResolver(root, os = "macos", arch = "aarch64")
        val identity = AgentReleaseIdentity("release", "a".repeat(64))
        val resolved = resolver.resolve(identity) as ProxyRuntimeResolution.Resolved
        assertEquals(executionDigest, resolved.runtime.agentExecutionManifestDigest)
        assertEquals("release", resolved.runtime.agentReleaseId)
        assertEquals("a".repeat(64), resolved.runtime.agentArchiveDigest)
        assertEquals(sha256Hex("agent-binary"), resolved.runtime.agentDigest)
        assertEquals("--managed", resolved.runtime.agentArgv!!.last())
        val absent = resolver.resolve(AgentReleaseIdentity("other", "a".repeat(64))) as ProxyRuntimeResolution.Resolved
        assertNull(absent.runtime.agentArgv)
        agent["entrypoint"] = listOf(prefix + "agent", "--different")
        writeManifest()
        assertTrue(resolver.resolve(identity) is ProxyRuntimeResolution.Failed)
        agent["entrypoint"] = listOf(prefix + "agent", "--managed")
        writeManifest()
        Files.writeString(root.resolve(prefix + "library-λ"), "changed")
        val tampered = resolver.resolve(identity) as ProxyRuntimeResolution.Failed
        assertEquals(ProxyRuntimeErrorCode.DIGEST_MISMATCH, tampered.error.code)
    }
}
