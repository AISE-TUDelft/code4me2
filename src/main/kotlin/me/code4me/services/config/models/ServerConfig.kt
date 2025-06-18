package me.code4me.services.config.models

import com.typesafe.config.Config

/**
 * Represents the server configuration to be used for initializing and connecting to a server.
 *
 * @property host The hostname or IP address of the server.
 * @property port The port number on which the server is running.
 * @property contextPath The base path or context for server requests.
 * @property timeout The timeout duration (in seconds) for server requests.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val contextPath: String,
    val timeout: Int,
) {
    companion object {
        fun fromConfig(config: Config): ServerConfig {
            return ServerConfig(
                host = config.getString("host"),
                port = config.getInt("port"),
                contextPath = config.getString("contextPath"),
                timeout = config.getInt("timeout"),
            )
        }
    }
}
