package com.pixelagents.watcher

import org.junit.Assert.*
import org.junit.Test

class JsonlFileWatcherTest {

    @Test
    fun testLineBufferingConcept() {
        // Simulate the line buffering logic used in readNewLines
        val text = "line1\nline2\npartial"
        val lines = text.split("\n")
        val buffer = lines.last() // "partial"
        val completeLines = lines.dropLast(1) // ["line1", "line2"]

        assertEquals("partial", buffer)
        assertEquals(2, completeLines.size)
        assertEquals("line1", completeLines[0])
        assertEquals("line2", completeLines[1])
    }

    @Test
    fun testLineBufferingWithTrailingNewline() {
        val text = "line1\nline2\n"
        val lines = text.split("\n")
        val buffer = lines.last() // ""
        val completeLines = lines.dropLast(1) // ["line1", "line2"]

        assertEquals("", buffer)
        assertEquals(2, completeLines.size)
    }

    @Test
    fun testLineBufferingWithPreviousBuffer() {
        val previousBuffer = "partial_jso"
        val newData = "n_data}\n{\"type\":\"user\"}\n"
        val text = previousBuffer + newData
        val lines = text.split("\n")
        val buffer = lines.last()
        val completeLines = lines.dropLast(1)

        assertEquals("", buffer)
        assertEquals(2, completeLines.size)
        assertEquals("partial_json_data}", completeLines[0])
        assertEquals("{\"type\":\"user\"}", completeLines[1])
    }
}
