package com.ffai

import org.junit.Test
import org.junit.Assert.*

/**
 * Example unit tests for FFAI components.
 * Add more tests as the project grows.
 */
class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun stringConcatenation_isCorrect() {
        val result = "Hello" + " " + "FFAI"
        assertEquals("Hello FFAI", result)
    }

    @Test
    fun listOperations_areCorrect() {
        val list = listOf(1, 2, 3, 4, 5)
        assertEquals(5, list.size)
        assertTrue(list.contains(3))
        assertEquals(listOf(2, 4), list.filter { it % 2 == 0 })
    }

    @Test
    fun nullSafety_isCorrect() {
        val nullable: String? = null
        assertNull(nullable)
        assertNotNull(nullable ?: "default")
    }

    @Test
    fun dataClassOperations_areCorrect() {
        data class TestData(val name: String, val value: Int)

        val data1 = TestData("FFAI", 100)
        val data2 = TestData("FFAI", 100)

        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
        assertEquals("FFAI", data1.component1())
        assertEquals(100, data1.component2())
    }
}
