package me.code4me.utils.record

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Record Test Suite")
class RecordTest {
    @Nested
    @DisplayName("Record Construction")
    inner class ConstructionTests {
        @Test
        @DisplayName("Should create record with type only")
        fun shouldCreateRecordWithTypeOnly() {
            val record = Record(Record.Type.CONTEXT)

            Assertions.assertEquals(Record.Type.CONTEXT, record.type)
            Assertions.assertTrue(record.expanded.isEmpty())
        }

        @Test
        @DisplayName("Should create record with all types")
        fun shouldCreateRecordWithAllTypes() {
            val contextRecord = Record(Record.Type.CONTEXT)
            val behavioralRecord = Record(Record.Type.BEHAVIORAL_TELEMETRY)
            val contextualRecord = Record(Record.Type.CONTEXTUAL_TELEMETRY)

            Assertions.assertEquals(Record.Type.CONTEXT, contextRecord.type)
            Assertions.assertEquals(Record.Type.BEHAVIORAL_TELEMETRY, behavioralRecord.type)
            Assertions.assertEquals(Record.Type.CONTEXTUAL_TELEMETRY, contextualRecord.type)
        }
    }

    @Nested
    @DisplayName("EntryKey Tests")
    inner class EntryKeyTests {
        @Test
        @DisplayName("Should create EntryKey with name and type")
        fun shouldCreateEntryKeyWithNameAndType() {
            val key = Record.EntryKey("testKey", String::class.java)

            Assertions.assertEquals("testKey", key.name)
            Assertions.assertEquals(String::class.java, key.type)
        }

        @Test
        @DisplayName("Should have proper toString representation")
        fun shouldHaveProperToStringRepresentation() {
            val stringKey = Record.EntryKey("stringKey", String::class.java)
            val intKey = Record.EntryKey("intKey", Integer::class.java)

            Assertions.assertEquals("Key(name='stringKey', type=String)", stringKey.toString())
            Assertions.assertEquals("Key(name='intKey', type=Integer)", intKey.toString())
        }

        @Test
        @DisplayName("Should support data class equality")
        fun shouldSupportDataClassEquality() {
            val key1 = Record.EntryKey("test", String::class.java)
            val key2 = Record.EntryKey("test", String::class.java)
            val key3 = Record.EntryKey("test", Int::class.java)
            val key4 = Record.EntryKey("different", String::class.java)

            Assertions.assertEquals(key1, key2)
            Assertions.assertNotEquals(key1, key3)
            Assertions.assertNotEquals(key1, key4)
        }
    }

    @Nested
    @DisplayName("Companion Object Key Creation")
    inner class CompanionKeyCreationTests {
        @Test
        @DisplayName("Should create typed keys using companion object")
        fun shouldCreateTypedKeysUsingCompanionObject() {
            val stringKey = Record.key<String>("stringKey")
            val intKey = Record.key<Int>("intKey")
            val listKey = Record.key<List<String>>("listKey")

            Assertions.assertEquals("stringKey", stringKey.name)
            Assertions.assertEquals(String::class.java, stringKey.type)

            Assertions.assertEquals("intKey", intKey.name)
            Assertions.assertEquals(Integer::class.java, intKey.type)

            Assertions.assertEquals("listKey", listKey.name)
            Assertions.assertEquals(List::class.java, listKey.type)
        }
    }

    @Nested
    @DisplayName("Record Value Operations")
    inner class ValueOperationsTests {
        private val record = Record(Record.Type.CONTEXT)
        private val stringKey = Record.key<String>("stringKey")
        private val intKey = Record.key<Int>("intKey")
        private val listKey = Record.key<List<String>>("listKey")

        @Test
        @DisplayName("Should put and get values correctly")
        fun shouldPutAndGetValuesCorrectly() {
            val stringValue = "test string"
            val intValue = 42
            val listValue = listOf("item1", "item2")

            record.put(stringKey, stringValue)
            record.put(intKey, intValue)
            record.put(listKey, listValue)

            Assertions.assertEquals(stringValue, record.get<String>(stringKey))
            Assertions.assertEquals(intValue, record.get<Int>(intKey))
            Assertions.assertEquals(listValue, record.get<List<String>>(listKey))
        }

        @Test
        @DisplayName("Should return null for non-existent keys")
        fun shouldReturnNullForNonExistentKeys() {
            val nonExistentKey = Record.key<String>("nonExistent")

            Assertions.assertNull(record.get<String>(nonExistentKey))
        }

        @Test
        @DisplayName("Should check if key exists")
        fun shouldCheckIfKeyExists() {
            val existingKey = Record.key<String>("existing")
            val nonExistentKey = Record.key<String>("nonExistent")

            record.put(existingKey, "value")

            Assertions.assertTrue(record.containsKey(existingKey))
            Assertions.assertFalse(record.containsKey(nonExistentKey))
        }

        @Test
        @DisplayName("Should remove key when null value is put")
        fun shouldRemoveKeyWhenNullValueIsPut() {
            val key = Record.key<String>("testKey")
            record.put(key, "initial value")

            Assertions.assertTrue(record.containsKey(key))

            val removedValue = record.put(key, null)

            Assertions.assertEquals("initial value", removedValue)
            Assertions.assertFalse(record.containsKey(key))
            Assertions.assertNull(record.get<String>(key))
        }

        @Test
        @DisplayName("Should return previous value when updating")
        fun shouldReturnPreviousValueWhenUpdating() {
            val key = Record.key<String>("testKey")

            val initialPut = record.put(key, "first value")
            Assertions.assertNull(initialPut)

            val secondPut = record.put(key, "second value")
            Assertions.assertEquals("first value", secondPut)

            Assertions.assertEquals("second value", record.get<String>(key))
        }

        @Test
        @DisplayName("Should throw exception for type mismatch")
        fun shouldThrowExceptionForTypeMismatch() {
            val stringKey = Record.key<String>("stringKey")

            val exception =
                assertThrows<IllegalArgumentException> {
                    record.put(stringKey, 123) // Putting Int into String key
                }

            Assertions.assertTrue(exception.message!!.contains("does not accept value of type"))
        }

        @Test
        @DisplayName("Should handle inheritance correctly")
        fun shouldHandleInheritanceCorrectly() {
            val numberKey = Record.EntryKey("numberKey", Number::class.java)

            // Should accept Integer as it extends Number
            Assertions.assertDoesNotThrow {
                record.put(numberKey, 42)
            }

            // Should accept Double as it extends Number
            Assertions.assertDoesNotThrow {
                record.put(numberKey, 42.0)
            }
        }
    }

    @Nested
    @DisplayName("toString Method")
    inner class ToStringTests {
        @Test
        @DisplayName("Should have proper string representation for empty record")
        fun shouldHaveProperStringRepresentationForEmptyRecord() {
            val record = Record(Record.Type.CONTEXT)

            Assertions.assertEquals("Record(type=CONTEXT, expanded={})", record.toString())
        }

        @Test
        @DisplayName("Should have proper string representation for record with values")
        fun shouldHaveProperStringRepresentationForRecordWithValues() {
            val record = Record(Record.Type.BEHAVIORAL_TELEMETRY)
            val key = Record.key<String>("testKey")
            record.put(key, "testValue")

            val result = record.toString()
            Assertions.assertTrue(result.startsWith("Record(type=BEHAVIORAL_TELEMETRY, expanded="))
            Assertions.assertTrue(result.contains("testValue"))
        }
    }

    @Nested
    @DisplayName("Extension Functions")
    inner class ExtensionFunctionTests {
        @Test
        @DisplayName("Should convert record to map correctly")
        fun shouldConvertRecordToMapCorrectly() {
            val record = Record(Record.Type.CONTEXT)
            val stringKey = Record.key<String>("stringKey")
            val intKey = Record.key<Int>("intKey")

            record.put(stringKey, "test")
            record.put(intKey, 42)

            val map = record.toMap()

            Assertions.assertEquals(2, map.size)
            Assertions.assertEquals("test", map["stringKey"])
            Assertions.assertEquals(42, map["intKey"])
        }

        @Test
        @DisplayName("Should convert empty record to empty map")
        fun shouldConvertEmptyRecordToEmptyMap() {
            val record = Record(Record.Type.CONTEXT)
            val map = record.toMap()

            Assertions.assertTrue(map.isEmpty())
        }

        @Test
        @DisplayName("Should aggregate records by type correctly")
        fun shouldAggregateRecordsByTypeCorrectly() {
            val record1 = Record(Record.Type.CONTEXT)
            val record2 = Record(Record.Type.CONTEXT)
            val record3 = Record(Record.Type.BEHAVIORAL_TELEMETRY)

            val key1 = Record.key<String>("key1")
            val key2 = Record.key<String>("key2")
            val key3 = Record.key<Int>("key3")

            record1.put(key1, "value1")
            record2.put(key2, "value2")
            record3.put(key3, 42)

            val records = listOf(record1, record2, record3)
            val aggregated = records.aggregateByType()

            Assertions.assertEquals(2, aggregated.size)
            Assertions.assertTrue(aggregated.containsKey(Record.Type.CONTEXT))
            Assertions.assertTrue(aggregated.containsKey(Record.Type.BEHAVIORAL_TELEMETRY))

            val contextRecord = aggregated[Record.Type.CONTEXT]!!
            Assertions.assertEquals("value1", contextRecord.get<String>(key1))
            Assertions.assertEquals("value2", contextRecord.get<String>(key2))

            val behavioralRecord = aggregated[Record.Type.BEHAVIORAL_TELEMETRY]!!
            Assertions.assertEquals(42, behavioralRecord.get<Int>(key3))
        }

        @Test
        @DisplayName("Should handle empty list in aggregation")
        fun shouldHandleEmptyListInAggregation() {
            val emptyList = emptyList<Record>()
            val aggregated = emptyList.aggregateByType()

            Assertions.assertTrue(aggregated.isEmpty())
        }

        @Test
        @DisplayName("Should handle overlapping keys in aggregation")
        fun shouldHandleOverlappingKeysInAggregation() {
            val record1 = Record(Record.Type.CONTEXT)
            val record2 = Record(Record.Type.CONTEXT)

            val key = Record.key<String>("sameKey")

            record1.put(key, "firstValue")
            record2.put(key, "secondValue")

            val records = listOf(record1, record2)
            val aggregated = records.aggregateByType()

            val contextRecord = aggregated[Record.Type.CONTEXT]!!
            // The second value should overwrite the first
            Assertions.assertEquals("secondValue", contextRecord.get<String>(key))
        }
    }

    @Nested
    @DisplayName("Edge Cases and Integration")
    inner class EdgeCasesTests {
        @Test
        @DisplayName("Should handle complex nested types")
        fun shouldHandleComplexNestedTypes() {
            val record = Record(Record.Type.CONTEXTUAL_TELEMETRY)
            val mapKey = Record.key<Map<String, List<Int>>>("complexKey")

            val complexValue =
                mapOf(
                    "list1" to listOf(1, 2, 3),
                    "list2" to listOf(4, 5, 6),
                )

            record.put(mapKey, complexValue)

            val retrievedValue = record.get<Map<String, List<Int>>>(mapKey)
            Assertions.assertEquals(complexValue, retrievedValue)
        }

        @Test
        @DisplayName("Should maintain type safety with multiple operations")
        fun shouldMaintainTypeSafetyWithMultipleOperations() {
            val record = Record(Record.Type.CONTEXT)
            val stringKey = Record.key<String>("stringKey")
            val intKey = Record.key<Int>("intKey")

            record.put(stringKey, "initial")
            record.put(intKey, 100)

            // Update values
            record.put(stringKey, "updated")
            record.put(intKey, 200)

            // Verify final state
            Assertions.assertEquals("updated", record.get<String>(stringKey))
            Assertions.assertEquals(200, record.get<Int>(intKey))

            // Remove one key
            record.put(stringKey, null)

            Assertions.assertNull(record.get<String>(stringKey))
            Assertions.assertEquals(200, record.get<Int>(intKey))
        }

        @Test
        @DisplayName("Should work with custom objects")
        fun shouldWorkWithCustomObjects() {
            data class CustomObject(val id: Int, val name: String)

            val record = Record(Record.Type.CONTEXT)
            val customKey = Record.key<CustomObject>("customKey")
            val customValue = CustomObject(1, "test")

            record.put(customKey, customValue)
            val retrieved = record.get<CustomObject>(customKey)

            Assertions.assertEquals(customValue, retrieved)
        }
    }
}
