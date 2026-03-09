package com.pixelagents.parser

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.JsonObject

class TranscriptParserTest {

    @Test
    fun testFormatToolStatus_read() {
        val input = JsonObject().apply { addProperty("file_path", "/home/user/project/src/main.kt") }
        assertEquals("Reading main.kt", formatToolStatus("Read", input))
    }

    @Test
    fun testFormatToolStatus_edit() {
        val input = JsonObject().apply { addProperty("file_path", "/some/path/file.ts") }
        assertEquals("Editing file.ts", formatToolStatus("Edit", input))
    }

    @Test
    fun testFormatToolStatus_bash_truncation() {
        val input = JsonObject().apply { addProperty("command", "npm run build && npm run test && npm run lint") }
        val result = formatToolStatus("Bash", input)
        assertTrue(result.startsWith("Running: "))
        assertTrue(result.endsWith("\u2026"))
    }

    @Test
    fun testFormatToolStatus_bash_short() {
        val input = JsonObject().apply { addProperty("command", "ls -la") }
        assertEquals("Running: ls -la", formatToolStatus("Bash", input))
    }

    @Test
    fun testFormatToolStatus_glob() {
        assertEquals("Searching files", formatToolStatus("Glob", null))
    }

    @Test
    fun testFormatToolStatus_grep() {
        assertEquals("Searching code", formatToolStatus("Grep", null))
    }

    @Test
    fun testFormatToolStatus_task() {
        val input = JsonObject().apply { addProperty("description", "Analyze the codebase") }
        assertEquals("Subtask: Analyze the codebase", formatToolStatus("Task", input))
    }

    @Test
    fun testFormatToolStatus_unknown_tool() {
        assertEquals("Using CustomTool", formatToolStatus("CustomTool", null))
    }

    @Test
    fun testPermissionExemptTools() {
        assertTrue(PERMISSION_EXEMPT_TOOLS.contains("Task"))
        assertTrue(PERMISSION_EXEMPT_TOOLS.contains("AskUserQuestion"))
        assertFalse(PERMISSION_EXEMPT_TOOLS.contains("Read"))
        assertFalse(PERMISSION_EXEMPT_TOOLS.contains("Bash"))
    }
}
