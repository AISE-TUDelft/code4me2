package me.code4me.services.config.models

import com.typesafe.config.Config

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
