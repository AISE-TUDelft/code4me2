package me.code4me.services.app

import com.intellij.openapi.components.Service

/**
 * Main application service for the Code4Me plugin.
 *
 * This service is initialized when the plugin starts and serves as the entry point
 * for the plugin's functionality. It can be used to coordinate other services
 * and components in the application.
 *
 * Currently, this service only logs its initialization, but it can be extended
 * to provide more functionality as needed.
 */
@Service
class AppService {
    /**
     * Initializes the AppService.
     *
     * This initialization block is executed when the service is first created.
     * It logs a message to indicate that the service has been initialized.
     */
    init {
        println("AppService initialized")
    }
}
