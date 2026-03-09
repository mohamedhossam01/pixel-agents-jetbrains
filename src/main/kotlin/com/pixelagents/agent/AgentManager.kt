package com.pixelagents.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.pixelagents.jcef.MessageBridge
import com.pixelagents.model.AgentState
import com.pixelagents.model.PersistedAgent
import com.pixelagents.parser.TimerManager
import com.pixelagents.persistence.StatePersistence
import com.pixelagents.terminal.TerminalIntegration
import com.pixelagents.util.Constants
import com.pixelagents.watcher.JsonlFileWatcher
import java.io.File
import java.util.*
import java.util.concurrent.*

private val log = Logger.getInstance("com.pixelagents.agent.AgentManager")

class AgentManager(
    private val project: Project,
    private val bridge: MessageBridge,
    private val timerManager: TimerManager,
    private val persistence: StatePersistence,
    private val terminal: TerminalIntegration,
) {
    val agents = ConcurrentHashMap<Int, AgentState>()
    val fileWatcher = JsonlFileWatcher(agents, timerManager, bridge)
    private var nextAgentId = 1
    private var nextTerminalIndex = 1
    private var activeAgentId: Int? = null
    private val knownJsonlFiles = ConcurrentHashMap.newKeySet<String>()
    private val jsonlPollTimers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private var projectScanFuture: ScheduledFuture<*>? = null

    private val scheduler = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "pixel-agents-agent-mgr").apply { isDaemon = true }
    }

    fun getProjectDirPath(cwd: String? = null): String? {
        val workspacePath = cwd ?: project.basePath ?: return null
        val dirName = workspacePath.replace(Regex("[^a-zA-Z0-9-]"), "-")
        val projectDir = File(System.getProperty("user.home"), ".claude/projects/$dirName").absolutePath
        log.info("Project dir: $workspacePath -> $dirName")
        return projectDir
    }

    fun launchNewTerminal(folderPath: String? = null) {
        val cwd = folderPath ?: project.basePath
        val idx = nextTerminalIndex++
        val terminalName = "${Constants.TERMINAL_NAME_PREFIX} #$idx"
        val sessionId = UUID.randomUUID().toString()

        val projectDir = getProjectDirPath(cwd)
        if (projectDir == null) {
            log.info("No project dir, cannot track agent")
            return
        }

        // Pre-register expected JSONL file
        val expectedFile = File(projectDir, "$sessionId.jsonl").absolutePath
        knownJsonlFiles.add(expectedFile)

        // Create agent immediately
        val id = nextAgentId++
        val agent = AgentState(
            id = id,
            terminalName = terminalName,
            projectDir = projectDir,
            jsonlFile = expectedFile,
        )
        agents[id] = agent
        activeAgentId = id
        persistAgents()

        log.info("Agent $id: created for terminal $terminalName")
        bridge.postMessage("agentCreated", "id" to id)

        // Launch terminal
        terminal.launchClaudeTerminal(terminalName, sessionId, cwd)

        // Ensure project scan
        ensureProjectScan(projectDir)

        // Poll for JSONL file to appear
        val pollFuture = scheduler.scheduleAtFixedRate({
            try {
                val file = File(agent.jsonlFile)
                if (file.exists()) {
                    log.info("Agent $id: found JSONL file ${file.name}")
                    jsonlPollTimers.remove(id)?.cancel(false)
                    fileWatcher.startWatching(id, agent.jsonlFile)
                    fileWatcher.readNewLines(id)
                }
            } catch (_: Exception) {}
        }, Constants.JSONL_POLL_INTERVAL_MS, Constants.JSONL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        jsonlPollTimers[id] = pollFuture
    }

    fun removeAgent(agentId: Int) {
        val agent = agents[agentId] ?: return

        jsonlPollTimers.remove(agentId)?.cancel(false)
        fileWatcher.stopWatching(agentId)
        timerManager.cancelWaitingTimer(agentId)
        timerManager.cancelPermissionTimer(agentId)

        agents.remove(agentId)
        if (activeAgentId == agentId) activeAgentId = null
        persistAgents()
    }

    fun closeAgent(agentId: Int) {
        val agent = agents[agentId] ?: return
        terminal.closeTerminal(agent.terminalName)
        removeAgent(agentId)
        bridge.postMessage("agentClosed", "id" to agentId)
    }

    fun focusAgent(agentId: Int) {
        val agent = agents[agentId] ?: return
        terminal.focusTerminal(agent.terminalName)
    }

    fun restoreAgents() {
        val persisted = persistence.restoreAgents()
        if (persisted.isEmpty()) return

        val liveTerminals = terminal.getActiveTerminalNames()
        var maxId = 0
        var maxIdx = 0
        var restoredProjectDir: String? = null

        for (p in persisted) {
            if (!liveTerminals.contains(p.terminalName)) continue

            val agent = AgentState(
                id = p.id,
                terminalName = p.terminalName,
                projectDir = p.projectDir,
                jsonlFile = p.jsonlFile,
                folderName = p.folderName,
            )
            agents[p.id] = agent
            knownJsonlFiles.add(p.jsonlFile)
            log.info("Restored agent ${p.id} -> terminal \"${p.terminalName}\"")

            if (p.id > maxId) maxId = p.id
            val match = Regex("#(\\d+)$").find(p.terminalName)
            if (match != null) {
                val idx = match.groupValues[1].toInt()
                if (idx > maxIdx) maxIdx = idx
            }

            restoredProjectDir = p.projectDir

            // Start file watching, skipping to end of file
            val file = File(p.jsonlFile)
            if (file.exists()) {
                agent.fileOffset = file.length()
                fileWatcher.startWatching(p.id, p.jsonlFile)
            } else {
                val pollFuture = scheduler.scheduleAtFixedRate({
                    if (File(agent.jsonlFile).exists()) {
                        log.info("Restored agent ${p.id}: found JSONL file")
                        jsonlPollTimers.remove(p.id)?.cancel(false)
                        agent.fileOffset = File(agent.jsonlFile).length()
                        fileWatcher.startWatching(p.id, agent.jsonlFile)
                    }
                }, Constants.JSONL_POLL_INTERVAL_MS, Constants.JSONL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                jsonlPollTimers[p.id] = pollFuture
            }
        }

        if (maxId >= nextAgentId) nextAgentId = maxId + 1
        if (maxIdx >= nextTerminalIndex) nextTerminalIndex = maxIdx + 1
        persistAgents()

        if (restoredProjectDir != null) {
            ensureProjectScan(restoredProjectDir)
        }
    }

    fun sendExistingAgents() {
        val agentIds = agents.keys.sorted()
        val agentMeta = persistence.getAgentSeats()
        val folderNames = mutableMapOf<Int, String>()
        for ((id, agent) in agents) {
            agent.folderName?.let { folderNames[id] = it }
        }

        log.info("sendExistingAgents: agents=$agentIds")
        bridge.postMessage(
            "existingAgents",
            "agents" to agentIds,
            "agentMeta" to agentMeta,
            "folderNames" to folderNames
        )

        // Re-send current statuses
        for ((agentId, agent) in agents) {
            for ((toolId, status) in agent.activeToolStatuses) {
                bridge.postMessage(
                    "agentToolStart",
                    "id" to agentId,
                    "toolId" to toolId,
                    "status" to status
                )
            }
            if (agent.isWaiting) {
                bridge.postMessage("agentStatus", "id" to agentId, "status" to "waiting")
            }
        }
    }

    fun onTerminalClosed(terminalName: String) {
        for ((id, agent) in agents) {
            if (agent.terminalName == terminalName) {
                if (activeAgentId == id) activeAgentId = null
                removeAgent(id)
                bridge.postMessage("agentClosed", "id" to id)
                break
            }
        }
    }

    fun onTerminalFocused(terminalName: String) {
        activeAgentId = null
        for ((id, agent) in agents) {
            if (agent.terminalName == terminalName) {
                activeAgentId = id
                bridge.postMessage("agentSelected", "id" to id)
                break
            }
        }
    }

    private fun persistAgents() {
        val persisted = agents.values.map { a ->
            PersistedAgent(
                id = a.id,
                terminalName = a.terminalName,
                jsonlFile = a.jsonlFile,
                projectDir = a.projectDir,
                folderName = a.folderName,
            )
        }
        persistence.persistAgents(persisted)
    }

    private fun ensureProjectScan(projectDir: String) {
        if (projectScanFuture != null) return

        // Seed known files
        try {
            val dir = File(projectDir)
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "jsonl" }?.forEach {
                    knownJsonlFiles.add(it.absolutePath)
                }
            }
        } catch (_: Exception) {}

        projectScanFuture = scheduler.scheduleAtFixedRate({
            scanForNewJsonlFiles(projectDir)
        }, Constants.PROJECT_SCAN_INTERVAL_MS, Constants.PROJECT_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun scanForNewJsonlFiles(projectDir: String) {
        val dir = File(projectDir)
        if (!dir.exists()) return
        val files = dir.listFiles()?.filter { it.extension == "jsonl" } ?: return

        for (file in files) {
            val path = file.absolutePath
            if (!knownJsonlFiles.contains(path)) {
                knownJsonlFiles.add(path)
                if (activeAgentId != null) {
                    log.info("New JSONL detected: ${file.name}, reassigning to agent $activeAgentId")
                    reassignAgentToFile(activeAgentId!!, path)
                }
            }
        }
    }

    private fun reassignAgentToFile(agentId: Int, newFilePath: String) {
        val agent = agents[agentId] ?: return

        fileWatcher.stopWatching(agentId)
        timerManager.cancelWaitingTimer(agentId)
        timerManager.cancelPermissionTimer(agentId)
        timerManager.clearAgentActivity(agent, agentId)

        agent.jsonlFile = newFilePath
        agent.fileOffset = 0
        agent.lineBuffer = ""
        persistAgents()

        fileWatcher.startWatching(agentId, newFilePath)
        fileWatcher.readNewLines(agentId)
    }

    fun dispose() {
        projectScanFuture?.cancel(false)
        for (id in jsonlPollTimers.keys.toList()) {
            jsonlPollTimers.remove(id)?.cancel(false)
        }
        for (id in agents.keys.toList()) {
            removeAgent(id)
        }
        fileWatcher.dispose()
        scheduler.shutdownNow()
    }
}
