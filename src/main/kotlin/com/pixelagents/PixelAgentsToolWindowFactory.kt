package com.pixelagents

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.pixelagents.agent.AgentManager
import com.pixelagents.assets.AssetLoader
import com.pixelagents.jcef.MessageBridge
import com.pixelagents.jcef.WebviewServer
import com.pixelagents.parser.TimerManager
import com.pixelagents.persistence.LayoutWatcher
import com.pixelagents.persistence.StatePersistence
import com.pixelagents.persistence.loadLayout
import com.pixelagents.persistence.readLayoutFromFile
import com.pixelagents.persistence.writeLayoutToFile
import com.pixelagents.terminal.TerminalIntegration
import com.pixelagents.util.Constants
import com.pixelagents.util.JsonUtils
import java.awt.BorderLayout
import java.io.File
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants


private val log = Logger.getInstance("com.pixelagents.PixelAgentsToolWindowFactory")

class PixelAgentsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val panel = JPanel(BorderLayout())
            panel.add(JLabel(
                "<html><center>JCEF is not available.<br>Please use JetBrains Runtime (JBR) to enable the embedded browser.</center></html>",
                SwingConstants.CENTER
            ), BorderLayout.CENTER)
            val content = ContentFactory.getInstance().createContent(panel, "Pixel Agents", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        val pixelAgentsPanel = PixelAgentsPanel(project)
        val content = ContentFactory.getInstance().createContent(pixelAgentsPanel.component, "Pixel Agents", false)
        toolWindow.contentManager.addContent(content)

        // Cleanup on dispose
        toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                pixelAgentsPanel.dispose()
            }
        })
    }
}

private class PixelAgentsPanel(private val project: Project) {
    private val browser: JBCefBrowser
    private val bridge: MessageBridge
    private val timerManager: TimerManager
    private val agentManager: AgentManager
    private val persistence: StatePersistence
    private val terminal: TerminalIntegration
    private val webviewServer = WebviewServer()
    private val assetLoader = AssetLoader()
    private var layoutWatcher: LayoutWatcher? = null
    private var defaultLayout: Map<String, Any?>? = null
    @Volatile private var webviewReadyHandled = false

    val component: JPanel

    init {
        browser = JBCefBrowser()
        val url = webviewServer.start()
        browser.loadURL(url)
        persistence = StatePersistence(project)
        terminal = TerminalIntegration(project)

        bridge = MessageBridge(browser) { type, data ->
            handleWebviewMessage(type, data)
        }
        timerManager = TimerManager(bridge)
        agentManager = AgentManager(project, bridge, timerManager, persistence, terminal)

        component = JPanel(BorderLayout())
        component.add(browser.component, BorderLayout.CENTER)
    }

    private fun handleWebviewMessage(type: String, data: Map<String, Any?>) {
        when (type) {
            "webviewReady" -> {
                if (!webviewReadyHandled) {
                    webviewReadyHandled = true
                    onWebviewReady()
                }
            }
            "openClaude" -> {
                val folderPath = data["folderPath"] as? String
                ApplicationManager.getApplication().invokeLater {
                    agentManager.launchNewTerminal(folderPath)
                }
            }
            "focusAgent" -> {
                val id = (data["id"] as? Number)?.toInt() ?: return
                ApplicationManager.getApplication().invokeLater {
                    agentManager.focusAgent(id)
                }
            }
            "closeAgent" -> {
                val id = (data["id"] as? Number)?.toInt() ?: return
                ApplicationManager.getApplication().invokeLater {
                    agentManager.closeAgent(id)
                }
            }
            "saveAgentSeats" -> {
                @Suppress("UNCHECKED_CAST")
                val seats = data["seats"] as? Map<String, Any?> ?: return
                persistence.saveAgentSeats(seats)
            }
            "saveLayout" -> {
                @Suppress("UNCHECKED_CAST")
                val layout = data["layout"] as? Map<String, Any?> ?: return
                layoutWatcher?.markOwnWrite()
                writeLayoutToFile(layout)
            }
            "setSoundEnabled" -> {
                val enabled = data["enabled"] as? Boolean ?: return
                persistence.soundEnabled = enabled
            }
            "exportLayout" -> {
                ApplicationManager.getApplication().invokeLater {
                    exportLayout()
                }
            }
            "importLayout" -> {
                ApplicationManager.getApplication().invokeLater {
                    importLayout()
                }
            }
        }
    }

    private fun onWebviewReady() {
        // Restore agents
        agentManager.restoreAgents()

        // Send settings
        bridge.postMessage("settingsLoaded", "soundEnabled" to persistence.soundEnabled)

        // Ensure project scan
        val projectDir = agentManager.getProjectDirPath()
        if (projectDir != null) {
            // Load assets in background
            Thread({
                try {
                    // Load default layout
                    defaultLayout = assetLoader.loadDefaultLayout()

                    // Load and send all assets
                    assetLoader.loadAndSendAssets(bridge)
                } catch (e: Exception) {
                    log.error("Error loading assets", e)
                }

                // Send layout
                val layout = loadLayout(defaultLayout)
                if (layout != null) {
                    bridge.postMessage("layoutLoaded", "layout" to layout)
                }
                startLayoutWatcher()
            }, "pixel-agents-asset-loader").apply { isDaemon = true }.start()
        } else {
            // No project dir, still try to load assets
            Thread({
                try {
                    defaultLayout = assetLoader.loadDefaultLayout()
                    assetLoader.loadAndSendAssets(bridge)
                } catch (_: Exception) {}
                val layout = loadLayout(defaultLayout)
                if (layout != null) {
                    bridge.postMessage("layoutLoaded", "layout" to layout)
                }
                startLayoutWatcher()
            }, "pixel-agents-asset-loader").apply { isDaemon = true }.start()
        }

        // Send existing agents
        agentManager.sendExistingAgents()
    }

    private fun startLayoutWatcher() {
        if (layoutWatcher != null) return
        layoutWatcher = LayoutWatcher { layout ->
            log.info("External layout change - pushing to webview")
            bridge.postMessage("layoutLoaded", "layout" to layout)
        }
    }

    private fun exportLayout() {
        val layout = readLayoutFromFile()
        if (layout == null) {
            Messages.showWarningDialog(project, "No saved layout to export.", "Pixel Agents")
            return
        }
        val chooser = javax.swing.JFileChooser(System.getProperty("user.home"))
        chooser.dialogTitle = "Export Pixel Agents Layout"
        chooser.selectedFile = File(System.getProperty("user.home"), "pixel-agents-layout.json")
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("JSON Files", "json")
        if (chooser.showSaveDialog(component) == javax.swing.JFileChooser.APPROVE_OPTION) {
            val json = JsonUtils.toPrettyJson(layout)
            chooser.selectedFile.writeText(json, Charsets.UTF_8)
            Messages.showInfoMessage(project, "Layout exported successfully.", "Pixel Agents")
        }
    }

    private fun importLayout() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { it.extension == "json" }
            .withTitle("Import Pixel Agents Layout")
        val chosen = FileChooser.chooseFile(descriptor, project, null)
        if (chosen != null) {
            try {
                val raw = File(chosen.path).readText(Charsets.UTF_8)
                val imported = JsonUtils.toMap(raw)
                if (imported["version"] != 1.0 && imported["version"] != 1) {
                    Messages.showErrorDialog(project, "Invalid layout file (wrong version).", "Pixel Agents")
                    return
                }
                if (imported["tiles"] !is List<*>) {
                    Messages.showErrorDialog(project, "Invalid layout file (missing tiles).", "Pixel Agents")
                    return
                }
                layoutWatcher?.markOwnWrite()
                writeLayoutToFile(imported)
                bridge.postMessage("layoutLoaded", "layout" to imported)
                Messages.showInfoMessage(project, "Layout imported successfully.", "Pixel Agents")
            } catch (e: Exception) {
                Messages.showErrorDialog(project, "Failed to read or parse layout file.", "Pixel Agents")
            }
        }
    }

    fun dispose() {
        layoutWatcher?.dispose()
        layoutWatcher = null
        agentManager.dispose()
        timerManager.dispose()
        bridge.dispose()
        browser.dispose()
        webviewServer.stop()
    }
}
