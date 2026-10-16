package me.code4me.services.config.models

import com.typesafe.config.Config

/**
 * Represents the server configuration to be used for initializing and connecting to a server.
 *
 * @property host The hostname or IP address of the server.
 * @property port The port number on which the server is running.
 * @property contextPath The base path or context for server requests.
 * @property timeout The timeout duration (in seconds) for server requests.
 * @property acpRuntimeBaseUrl Optional backend URL handed to locally-running ACP agent runtimes.
 *   Needed when the agent process sits in a different network namespace than the IDE (e.g. a
 *   Docker container), where the plugin's own `host` is not reachable.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val contextPath: String,
    val timeout: Int,
    val acpRuntimeBaseUrl: String? = null,
) {
    companion object {
        fun fromConfig(config: Config): ServerConfig {
            val port: Int = if (config.hasPath("port")) config.getInt("port") else 0
            val contextPath: String = if (config.hasPath("contextPath")) config.getString("contextPath") else ""
            val acpRuntimeBaseUrl: String? =
                if (config.hasPath("acpRuntimeBaseUrl")) config.getString("acpRuntimeBaseUrl") else null
            return ServerConfig(
                host = config.getString("host"),
                port = port,
                contextPath = contextPath,
                timeout = config.getInt("timeout"),
                acpRuntimeBaseUrl = acpRuntimeBaseUrl,
            )
        }
    }
}
