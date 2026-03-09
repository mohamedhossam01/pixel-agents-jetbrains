package com.pixelagents.model

import java.util.concurrent.ConcurrentHashMap

data class AgentState(
    val id: Int,
    val terminalName: String,
    val projectDir: String,
    var jsonlFile: String,
    var fileOffset: Long = 0,
    var lineBuffer: String = "",
    val activeToolIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val activeToolStatuses: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    val activeToolNames: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    val activeSubagentToolIds: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap(),
    val activeSubagentToolNames: ConcurrentHashMap<String, ConcurrentHashMap<String, String>> = ConcurrentHashMap(),
    var isWaiting: Boolean = false,
    var permissionSent: Boolean = false,
    var hadToolsInTurn: Boolean = false,
    val folderName: String? = null,
)
