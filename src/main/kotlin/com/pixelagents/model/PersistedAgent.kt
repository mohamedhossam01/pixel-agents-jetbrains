package com.pixelagents.model

data class PersistedAgent(
    val id: Int,
    val terminalName: String,
    val jsonlFile: String,
    val projectDir: String,
    val folderName: String? = null,
)
