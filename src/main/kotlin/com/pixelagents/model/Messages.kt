package com.pixelagents.model

/**
 * Message types exchanged between host (Kotlin) and webview (JS).
 *
 * Host -> Webview:
 *   agentCreated, agentClosed, agentSelected, existingAgents,
 *   agentToolStart, agentToolDone, agentToolsClear,
 *   agentStatus, agentToolPermission, agentToolPermissionClear,
 *   subagentToolStart, subagentToolDone, subagentClear, subagentToolPermission,
 *   layoutLoaded, characterSpritesLoaded, floorTilesLoaded, wallTilesLoaded,
 *   furnitureAssetsLoaded, settingsLoaded, workspaceFolders
 *
 * Webview -> Host:
 *   webviewReady, openClaude, focusAgent, closeAgent,
 *   saveAgentSeats, saveLayout, setSoundEnabled,
 *   exportLayout, importLayout
 */
data class WebviewMessage(
    val type: String,
    val id: Int? = null,
    val status: String? = null,
    val toolId: String? = null,
    val parentToolId: String? = null,
    val layout: Map<String, Any?>? = null,
    val seats: Map<String, Any?>? = null,
    val enabled: Boolean? = null,
    val folderPath: String? = null,
    val folderName: String? = null,
    val agents: List<Int>? = null,
    val agentMeta: Map<String, Any?>? = null,
    val folderNames: Map<Int, String>? = null,
    val characters: Any? = null,
    val sprites: Any? = null,
    val catalog: Any? = null,
    val soundEnabled: Boolean? = null,
    val folders: Any? = null,
)
