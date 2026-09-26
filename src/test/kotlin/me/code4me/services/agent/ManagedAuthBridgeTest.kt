package me.code4me.services.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class ManagedAuthBridgeTest {
    @Test
    fun `register fails when workspace vanished before registration`() {
        val directory = Files.createTempDirectory("managed-bridge-missing-workspace")
        val project = mock<Project>()
        whenever(project.basePath).thenReturn(directory.resolve("vanished").toString())
        val bridge = ManagedAuthBridge(directory)

        try {
            assertThrows(IllegalStateException::class.java) { bridge.register(project) }
            assertFalse(Files.exists(bridge.discoveryPath))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun `discovery is private and grant endpoint requires capability`() {
        val directory = Files.createTempDirectory("managed-bridge")
        val bridge = ManagedAuthBridge(directory, backendUrlProvider = { "https://example.test" })
        bridge.start()
        try {
            val discovery = Json.parseToJsonElement(Files.readString(bridge.discoveryPath)).jsonObject
            assertEquals("1", discovery.getValue("protocol_version").jsonPrimitive.content)
            assertTrue(discovery.getValue("base_url").jsonPrimitive.content.startsWith("http://127.0.0.1:"))
            val request = HttpRequest.newBuilder(URI("${bridge.baseUrl}/v1/grant"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(401, response.statusCode())

            val capability = discovery.getValue("capability").jsonPrimitive.content
            val statusRequest = HttpRequest.newBuilder(URI("${bridge.baseUrl}/v1/status"))
                .header("Authorization", "Bearer $capability")
                .GET()
                .build()
            val statusResponse = HttpClient.newHttpClient()
                .send(statusRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, statusResponse.statusCode())

            val authenticatedRequest = HttpRequest.newBuilder(URI("${bridge.baseUrl}/v1/grant"))
                .header("Authorization", "Bearer $capability")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val authenticatedResponse = HttpClient.newHttpClient()
                .send(authenticatedRequest, HttpResponse.BodyHandlers.ofString())
            // Authentication state lookup is deliberately fail-closed outside a real IDE.
            assertEquals(401, authenticatedResponse.statusCode())
        } finally {
            bridge.close()
        }
        assertFalse(Files.exists(bridge.discoveryPath))
    }
}
