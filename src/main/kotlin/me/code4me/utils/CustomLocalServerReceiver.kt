package me.code4me.utils

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom implementation of VerificationCodeReceiver that logs startup and exposes failures.
 * This is a replacement for the default LocalServerReceiver that uses Jetty under the hood.
 */
class CustomLocalServerReceiver private constructor(
    private val host: String,
    private val port: Int,
    private val callbackPath: String,
    private val timeout: Int,
) : VerificationCodeReceiver {
    private val logger = Logger.getInstance(CustomLocalServerReceiver::class.java)
    private var serverSocket: ServerSocket? = null
    private var code: String? = null
    private val semaphore = Semaphore(0)
    private var error: Exception? = null
    private var isRunning = AtomicBoolean(false)

    /**
     * Returns the redirect URI to use for the OAuth authorization flow.
     */
    override fun getRedirectUri(): String {
        val path = if (callbackPath.startsWith("/")) callbackPath else "/$callbackPath"
        return "http://$host:$port$path"
    }

    /**
     * Waits for the authorization code to be received.
     * This method blocks until the code is received or an error occurs.
     */
    @Throws(IOException::class)
    override fun waitForCode(): String {
        startServer()

        try {
            // Wait for the code to be received or timeout
            if (!semaphore.tryAcquire(timeout.toLong(), TimeUnit.MILLISECONDS)) {
                logger.warn("Timeout waiting for authorization code")
                throw IOException("Timeout waiting for authorization code")
            }

            // If there was an error during server operation, throw it
            error?.let { throw it }

            // Return the received code
            return code ?: throw IOException("No authorization code received")
        } catch (e: InterruptedException) {
            logger.warn("Interrupted while waiting for authorization code", e)
            throw IOException("Interrupted while waiting for authorization code", e)
        }
    }

    /**
     * Stops the server.
     */
    override fun stop() {
        logger.info("Stopping authorization code receiver")
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            logger.warn("Failed to close server socket", e)
        }
    }

    /**
     * Starts the server to listen for the authorization code.
     */
    @Throws(IOException::class)
    internal fun startServer() {
        try {
            logger.info("Starting authorization code receiver on $host:$port$callbackPath")
            serverSocket = ServerSocket(port)

            // Start a thread to handle the server socket
            Thread {
                try {
                    val socket = serverSocket?.accept()
                    socket?.use { handleConnection(it) }
                } catch (e: SocketException) {
                    // Socket was closed, which is expected when stop() is called
                    logger.info("Server socket closed")
                } catch (e: Exception) {
                    logger.error("Error accepting connection", e)
                    error = e
                    semaphore.release()
                }
            }.start()

            // start a thread to check that the server is running
            Thread {
                try {
                    // Wait for a short time to ensure the server is running
                    Thread.sleep(1000)

                    if (serverSocket?.isBound == true) {
                        logger.info("Authorization code receiver started successfully")
                    } else {
                        logger.error("Failed to start authorization code receiver")
                        throw IOException("Failed to start authorization code receiver")
                    }
                } catch (e: InterruptedException) {
                    logger.warn("Interrupted while waiting for server to start", e)
                }
            }.start()

            logger.info("Authorization code receiver started successfully")
        } catch (e: IOException) {
            logger.error("Failed to start authorization code receiver", e)
            throw IOException("Failed to start authorization code receiver: ${e.message}", e)
        }
    }

    /**
     * Handles an incoming connection.
     */
    private fun handleConnection(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()

            // Read the first line of the request
            val requestLine = reader.readLine() ?: ""
            logger.info("Received request: $requestLine")

            // Parse the request to extract the code parameter
            if (requestLine.startsWith("GET ")) {
                val uri = requestLine.split(" ")[1]
                if (uri.contains("?")) {
                    val query = uri.substringAfter("?")
                    val params = query.split("&")
                    for (param in params) {
                        if (param.startsWith("code=")) {
                            code = param.substringAfter("code=")
                            break
                        }
                    }
                }
            }

            // Send a response
            val response =
                if (code != null) {
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html\r\n\r\n" +
                        "<html><body><h1>Authorization Successful</h1>" +
                        "<p>You can close this window and return to the application.</p></body></html>"
                } else {
                    "HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Type: text/html\r\n\r\n" +
                        "<html><body><h1>Authorization Failed</h1>" +
                        "<p>No authorization code was received. Please try again.</p></body></html>"
                }

            writer.write(response)
            writer.flush()

            // Signal that we've received the code (or failed to)
            semaphore.release()
        } catch (e: Exception) {
            logger.error("Error handling connection", e)
            error = e
            semaphore.release()
        }
    }

    /**
     * Builder for CustomLocalServerReceiver.
     */
    class Builder {
        private var host: String = "localhost"
        private var port: Int = 8008
        private var callbackPath: String = "/oauth2callback"
        private var timeout: Int = 60000 // 1 minute timeout by default

        fun setHost(host: String): Builder {
            this.host = host
            return this
        }

        fun setPort(port: Int): Builder {
            this.port = port
            return this
        }

        fun setCallbackPath(callbackPath: String): Builder {
            this.callbackPath = callbackPath
            return this
        }

        fun setTimeout(timeout: Int): Builder {
            this.timeout = timeout
            return this
        }

        fun build(): CustomLocalServerReceiver {
            return CustomLocalServerReceiver(host, port, callbackPath, timeout)
        }
    }
}
