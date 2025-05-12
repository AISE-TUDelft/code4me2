import me.code4me.services.modules.aggregators.BaseContextAggregator
import me.code4me.services.modules.aggregators.BaseTelemetryAggregator
import me.code4me.services.modules.context.BasicContextRetrievalModule
import me.code4me.services.modules.manager.ModuleManager
import me.code4me.services.modules.telemetry.BasicTelemetryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPORTANT: This test class needs to be modified whenever the structure of the modules changes.
 * For example, if a new module is added or an existing module is removed, the tests should be updated accordingly.
 */
class ModulesStructureTest {
    @Test
    fun testModuleNameTel() {
        val module = BasicTelemetryModule()
        assertEquals("BasicTelemetryModule", module.moduleName)
    }

    @Test
    fun testModuleNameCon() {
        val module = BasicContextRetrievalModule()
        assertEquals("BasicContextRetrievalModule", module.moduleName)
    }

//    @Test
//    fun testModuleNameModuleManager() {
//        val module = ModuleManager()
//        assertEquals("ModuleManager", module.moduleName)
//    }
//
//    @Test
//    fun testModuleManagerInitialization() {
//        val moduleManager = ModuleManager()
//        assertNotNull(moduleManager)
//        assertEquals(2, moduleManager.getAggregators().size)
//    }
//
//    @Test
//    fun testAggregators() {
//        val moduleManager = ModuleManager()
//        val aggregators = moduleManager.getAggregators()
//        assertTrue(aggregators.any { it is BaseTelemetryAggregator })
//        assertTrue(aggregators.any { it is BaseContextAggregator })
//    }
//
//    @Test
//    fun testAggregatorsModules() {
//        val moduleManager = ModuleManager()
//        val aggregators = moduleManager.getAggregators()
//        assertTrue(aggregators.any { it is BaseTelemetryAggregator })
//        assertTrue(aggregators.any { it is BaseContextAggregator })
//
//        val telemetryAggregator = aggregators.find { it is BaseTelemetryAggregator } as BaseTelemetryAggregator
//        val contextAggregator = aggregators.find { it is BaseContextAggregator } as BaseContextAggregator
//
//        assertNotNull(telemetryAggregator)
//        assertNotNull(contextAggregator)
//
//        assertEquals(1, telemetryAggregator.retrieveModules().size)
//        assertEquals(1, contextAggregator.retrieveModules().size)
//
//        assertTrue(telemetryAggregator.retrieveModules().any { it is BasicTelemetryModule })
//        assertTrue(contextAggregator.retrieveModules().any { it is BasicContextRetrievalModule })
//    }
//
//    @Test
//    fun testCollectData() {
//        val moduleManager = ModuleManager()
//        val data = moduleManager.collectData()
//        assertNotNull(data)
//        assertTrue(data.isNotEmpty())
//        assertEquals(2, data.size)
//        assertTrue(data.any { it.type == Record.Type.TELEMETRY })
//        assertTrue(data.any { it.type == Record.Type.CONTEXT })
//    }
}
