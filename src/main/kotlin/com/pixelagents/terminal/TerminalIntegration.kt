package com.pixelagents.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

private val log = Logger.getInstance("com.pixelagents.terminal.TerminalIntegration")

class TerminalIntegration(private val project: Project) {

    /**
     * Create a new terminal running Claude Code with a session ID.
     * Returns the terminal tab name.
     */
    @Suppress("DEPRECATION")
    fun launchClaudeTerminal(name: String, sessionId: String, cwd: String?): String? {
        return try {
            val manager = TerminalToolWindowManager.getInstance(project)
            // Use the deprecated TerminalView API for createLocalShellWidget
            // (the non-deprecated API varies across IDE versions)
            val terminalView = org.jetbrains.plugins.terminal.TerminalView.getInstance(project)
            val widget = terminalView.createLocalShellWidget(
                cwd ?: project.basePath ?: System.getProperty("user.home"),
                name
            )
            widget.executeCommand("claude --session-id $sessionId")
            log.info("Launched Claude terminal: $name with session $sessionId")
            name
        } catch (e: Exception) {
            log.error("Failed to launch Claude terminal", e)
            null
        }
    }

    /**
     * Focus an existing terminal by name.
     */
    fun focusTerminal(terminalName: String) {
        try {
            val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
            toolWindow.show()

            // Try to find and activate the tab with this name
            val contentManager = toolWindow.contentManager
            for (content in contentManager.contents) {
                if (content.displayName == terminalName) {
                    contentManager.setSelectedContent(content)
                    break
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to focus terminal $terminalName: ${e.message}")
        }
    }

    /**
     * Close a terminal by name.
     */
    fun closeTerminal(terminalName: String) {
        try {
            val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
            val contentManager = toolWindow.contentManager
            for (content in contentManager.contents) {
                if (content.displayName == terminalName) {
                    contentManager.removeContent(content, true)
                    break
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to close terminal $terminalName: ${e.message}")
        }
    }

    /**
     * Get names of all currently open terminals.
     */
    fun getActiveTerminalNames(): List<String> {
        return try {
            val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return emptyList()
            toolWindow.contentManager.contents.map { it.displayName }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
