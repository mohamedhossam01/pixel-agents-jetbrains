package com.pixelagents.parser

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.pixelagents.jcef.MessageBridge
import com.pixelagents.model.AgentState
import com.pixelagents.util.Constants
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

val PERMISSION_EXEMPT_TOOLS = setOf("Task", "AskUserQuestion")

private val log = Logger.getInstance("com.pixelagents.parser.TranscriptParser")

fun formatToolStatus(toolName: String, input: JsonObject?): String {
    fun baseName(key: String): String {
        val p = input?.get(key)?.asString ?: return ""
        return File(p).name
    }

    return when (toolName) {
        "Read" -> "Reading ${baseName("file_path")}"
        "Edit" -> "Editing ${baseName("file_path")}"
        "Write" -> "Writing ${baseName("file_path")}"
        "Bash" -> {
            val cmd = input?.get("command")?.asString ?: ""
            val truncated = if (cmd.length > Constants.BASH_COMMAND_DISPLAY_MAX_LENGTH)
                cmd.substring(0, Constants.BASH_COMMAND_DISPLAY_MAX_LENGTH) + "\u2026"
            else cmd
            "Running: $truncated"
        }
        "Glob" -> "Searching files"
        "Grep" -> "Searching code"
        "WebFetch" -> "Fetching web content"
        "WebSearch" -> "Searching the web"
        "Task" -> {
            val desc = input?.get("description")?.asString ?: ""
            if (desc.isNotEmpty()) {
                val truncated = if (desc.length > Constants.TASK_DESCRIPTION_DISPLAY_MAX_LENGTH)
                    desc.substring(0, Constants.TASK_DESCRIPTION_DISPLAY_MAX_LENGTH) + "\u2026"
                else desc
                "Subtask: $truncated"
            } else "Running subtask"
        }
        "AskUserQuestion" -> "Waiting for your answer"
        "EnterPlanMode" -> "Planning"
        "NotebookEdit" -> "Editing notebook"
        else -> "Using $toolName"
    }
}

class TimerManager(private val bridge: MessageBridge) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pixel-agents-timers").apply { isDaemon = true }
    }
    val waitingTimers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    val permissionTimers = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    fun cancelWaitingTimer(agentId: Int) {
        waitingTimers.remove(agentId)?.cancel(false)
    }

    fun startWaitingTimer(agentId: Int, delayMs: Long, agents: ConcurrentHashMap<Int, AgentState>) {
        cancelWaitingTimer(agentId)
        val future = scheduler.schedule({
            waitingTimers.remove(agentId)
            agents[agentId]?.isWaiting = true
            bridge.postMessage("agentStatus", "id" to agentId, "status" to "waiting")
        }, delayMs, TimeUnit.MILLISECONDS)
        waitingTimers[agentId] = future
    }

    fun cancelPermissionTimer(agentId: Int) {
        permissionTimers.remove(agentId)?.cancel(false)
    }

    fun startPermissionTimer(agentId: Int, agents: ConcurrentHashMap<Int, AgentState>) {
        cancelPermissionTimer(agentId)
        val future = scheduler.schedule({
            permissionTimers.remove(agentId)
            val agent = agents[agentId] ?: return@schedule

            // Check for active non-exempt tools (parent level)
            var hasNonExempt = agent.activeToolIds.any { toolId ->
                val toolName = agent.activeToolNames[toolId] ?: ""
                !PERMISSION_EXEMPT_TOOLS.contains(toolName)
            }

            // Check sub-agent tools
            val stuckSubagentParentIds = mutableListOf<String>()
            for ((parentToolId, subNames) in agent.activeSubagentToolNames) {
                for ((_, toolName) in subNames) {
                    if (!PERMISSION_EXEMPT_TOOLS.contains(toolName)) {
                        stuckSubagentParentIds.add(parentToolId)
                        hasNonExempt = true
                        break
                    }
                }
            }

            if (hasNonExempt) {
                agent.permissionSent = true
                log.info("Agent $agentId: possible permission wait detected")
                bridge.postMessage("agentToolPermission", "id" to agentId)
                for (parentToolId in stuckSubagentParentIds) {
                    bridge.postMessage(
                        "subagentToolPermission",
                        "id" to agentId,
                        "parentToolId" to parentToolId
                    )
                }
            }
        }, Constants.PERMISSION_TIMER_DELAY_MS, TimeUnit.MILLISECONDS)
        permissionTimers[agentId] = future
    }

    fun clearAgentActivity(agent: AgentState, agentId: Int) {
        agent.activeToolIds.clear()
        agent.activeToolStatuses.clear()
        agent.activeToolNames.clear()
        agent.activeSubagentToolIds.clear()
        agent.activeSubagentToolNames.clear()
        agent.isWaiting = false
        agent.permissionSent = false
        cancelPermissionTimer(agentId)
        bridge.postMessage("agentToolsClear", "id" to agentId)
        bridge.postMessage("agentStatus", "id" to agentId, "status" to "active")
    }

    fun dispose() {
        scheduler.shutdownNow()
    }
}

fun processTranscriptLine(
    agentId: Int,
    line: String,
    agents: ConcurrentHashMap<Int, AgentState>,
    timerManager: TimerManager,
    bridge: MessageBridge,
) {
    val agent = agents[agentId] ?: return
    try {
        val record = JsonParser.parseString(line).asJsonObject
        val type = record.get("type")?.asString ?: return

        when (type) {
            "assistant" -> processAssistantRecord(agentId, record, agent, agents, timerManager, bridge)
            "progress" -> processProgressRecord(agentId, record, agent, agents, timerManager, bridge)
            "user" -> processUserRecord(agentId, record, agent, agents, timerManager, bridge)
            "system" -> {
                if (record.get("subtype")?.asString == "turn_duration") {
                    timerManager.cancelWaitingTimer(agentId)
                    timerManager.cancelPermissionTimer(agentId)

                    if (agent.activeToolIds.isNotEmpty()) {
                        agent.activeToolIds.clear()
                        agent.activeToolStatuses.clear()
                        agent.activeToolNames.clear()
                        agent.activeSubagentToolIds.clear()
                        agent.activeSubagentToolNames.clear()
                        bridge.postMessage("agentToolsClear", "id" to agentId)
                    }

                    agent.isWaiting = true
                    agent.permissionSent = false
                    agent.hadToolsInTurn = false
                    bridge.postMessage("agentStatus", "id" to agentId, "status" to "waiting")
                }
            }
        }
    } catch (e: Exception) {
        // Ignore malformed lines
    }
}

private fun processAssistantRecord(
    agentId: Int,
    record: JsonObject,
    agent: AgentState,
    agents: ConcurrentHashMap<Int, AgentState>,
    timerManager: TimerManager,
    bridge: MessageBridge,
) {
    val content = record.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
    val hasToolUse = content.any { it.asJsonObject.get("type")?.asString == "tool_use" }

    if (hasToolUse) {
        timerManager.cancelWaitingTimer(agentId)
        agent.isWaiting = false
        agent.hadToolsInTurn = true
        bridge.postMessage("agentStatus", "id" to agentId, "status" to "active")

        var hasNonExemptTool = false
        for (element in content) {
            val block = element.asJsonObject
            if (block.get("type")?.asString == "tool_use" && block.has("id")) {
                val toolId = block.get("id").asString
                val toolName = block.get("name")?.asString ?: ""
                val input = block.getAsJsonObject("input")
                val status = formatToolStatus(toolName, input)

                log.info("Agent $agentId tool start: $toolId $status")
                agent.activeToolIds.add(toolId)
                agent.activeToolStatuses[toolId] = status
                agent.activeToolNames[toolId] = toolName

                if (!PERMISSION_EXEMPT_TOOLS.contains(toolName)) {
                    hasNonExemptTool = true
                }

                bridge.postMessage(
                    "agentToolStart",
                    "id" to agentId,
                    "toolId" to toolId,
                    "status" to status
                )
            }
        }
        if (hasNonExemptTool) {
            timerManager.startPermissionTimer(agentId, agents)
        }
    } else if (content.any { it.asJsonObject.get("type")?.asString == "text" } && !agent.hadToolsInTurn) {
        timerManager.startWaitingTimer(agentId, Constants.TEXT_IDLE_DELAY_MS, agents)
    }
}

private fun processUserRecord(
    agentId: Int,
    record: JsonObject,
    agent: AgentState,
    agents: ConcurrentHashMap<Int, AgentState>,
    timerManager: TimerManager,
    bridge: MessageBridge,
) {
    val content = record.getAsJsonObject("message")?.get("content") ?: return

    if (content.isJsonArray) {
        val blocks = content.asJsonArray
        val hasToolResult = blocks.any { it.asJsonObject.get("type")?.asString == "tool_result" }

        if (hasToolResult) {
            for (element in blocks) {
                val block = element.asJsonObject
                if (block.get("type")?.asString == "tool_result" && block.has("tool_use_id")) {
                    val completedToolId = block.get("tool_use_id").asString
                    log.info("Agent $agentId tool done: $completedToolId")

                    // If completed tool was a Task, clear its subagent tools
                    if (agent.activeToolNames[completedToolId] == "Task") {
                        agent.activeSubagentToolIds.remove(completedToolId)
                        agent.activeSubagentToolNames.remove(completedToolId)
                        bridge.postMessage(
                            "subagentClear",
                            "id" to agentId,
                            "parentToolId" to completedToolId
                        )
                    }

                    agent.activeToolIds.remove(completedToolId)
                    agent.activeToolStatuses.remove(completedToolId)
                    agent.activeToolNames.remove(completedToolId)

                    // Delay tool done message (300ms)
                    val toolId = completedToolId
                    Thread {
                        Thread.sleep(Constants.TOOL_DONE_DELAY_MS)
                        bridge.postMessage(
                            "agentToolDone",
                            "id" to agentId,
                            "toolId" to toolId
                        )
                    }.apply { isDaemon = true }.start()
                }
            }
            if (agent.activeToolIds.isEmpty()) {
                agent.hadToolsInTurn = false
            }
        } else {
            // New user text prompt
            timerManager.cancelWaitingTimer(agentId)
            timerManager.clearAgentActivity(agent, agentId)
            agent.hadToolsInTurn = false
        }
    } else if (content.isJsonPrimitive) {
        val text = content.asString
        if (text.isNotBlank()) {
            timerManager.cancelWaitingTimer(agentId)
            timerManager.clearAgentActivity(agent, agentId)
            agent.hadToolsInTurn = false
        }
    }
}

private fun processProgressRecord(
    agentId: Int,
    record: JsonObject,
    agent: AgentState,
    agents: ConcurrentHashMap<Int, AgentState>,
    timerManager: TimerManager,
    bridge: MessageBridge,
) {
    val parentToolId = record.get("parentToolUseID")?.asString ?: return
    val data = record.getAsJsonObject("data") ?: return
    val dataType = data.get("type")?.asString

    // bash_progress / mcp_progress: tool is executing, restart permission timer
    if (dataType == "bash_progress" || dataType == "mcp_progress") {
        if (agent.activeToolIds.contains(parentToolId)) {
            timerManager.startPermissionTimer(agentId, agents)
        }
        return
    }

    // Verify parent is an active Task tool (agent_progress)
    if (agent.activeToolNames[parentToolId] != "Task") return

    val msg = data.getAsJsonObject("message") ?: return
    val msgType = msg.get("type")?.asString ?: return
    val innerMsg = msg.getAsJsonObject("message") ?: return
    val content = innerMsg.getAsJsonArray("content") ?: return

    when (msgType) {
        "assistant" -> {
            var hasNonExemptSubTool = false
            for (element in content) {
                val block = element.asJsonObject
                if (block.get("type")?.asString == "tool_use" && block.has("id")) {
                    val toolId = block.get("id").asString
                    val toolName = block.get("name")?.asString ?: ""
                    val input = block.getAsJsonObject("input")
                    val status = formatToolStatus(toolName, input)

                    log.info("Agent $agentId subagent tool start: $toolId $status (parent: $parentToolId)")

                    agent.activeSubagentToolIds
                        .getOrPut(parentToolId) { ConcurrentHashMap.newKeySet() }
                        .add(toolId)
                    agent.activeSubagentToolNames
                        .getOrPut(parentToolId) { ConcurrentHashMap() }[toolId] = toolName

                    if (!PERMISSION_EXEMPT_TOOLS.contains(toolName)) {
                        hasNonExemptSubTool = true
                    }

                    bridge.postMessage(
                        "subagentToolStart",
                        "id" to agentId,
                        "parentToolId" to parentToolId,
                        "toolId" to toolId,
                        "status" to status
                    )
                }
            }
            if (hasNonExemptSubTool) {
                timerManager.startPermissionTimer(agentId, agents)
            }
        }
        "user" -> {
            for (element in content) {
                val block = element.asJsonObject
                if (block.get("type")?.asString == "tool_result" && block.has("tool_use_id")) {
                    val toolId = block.get("tool_use_id").asString
                    log.info("Agent $agentId subagent tool done: $toolId (parent: $parentToolId)")

                    agent.activeSubagentToolIds[parentToolId]?.remove(toolId)
                    agent.activeSubagentToolNames[parentToolId]?.remove(toolId)

                    Thread {
                        Thread.sleep(300)
                        bridge.postMessage(
                            "subagentToolDone",
                            "id" to agentId,
                            "parentToolId" to parentToolId,
                            "toolId" to toolId
                        )
                    }.apply { isDaemon = true }.start()
                }
            }

            // Check if still has non-exempt sub-agent tools
            var stillHasNonExempt = false
            for ((_, subNames) in agent.activeSubagentToolNames) {
                for ((_, toolName) in subNames) {
                    if (!PERMISSION_EXEMPT_TOOLS.contains(toolName)) {
                        stillHasNonExempt = true
                        break
                    }
                }
                if (stillHasNonExempt) break
            }
            if (stillHasNonExempt) {
                timerManager.startPermissionTimer(agentId, agents)
            }
        }
    }
}
