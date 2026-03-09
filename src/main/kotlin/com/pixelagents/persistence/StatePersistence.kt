package com.pixelagents.persistence

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.pixelagents.model.PersistedAgent
import com.pixelagents.util.Constants
import com.pixelagents.util.JsonUtils

private val log = Logger.getInstance("com.pixelagents.persistence.StatePersistence")

class StatePersistence(private val project: Project) {

    private val projectProps: PropertiesComponent
        get() = PropertiesComponent.getInstance(project)

    private val appProps: PropertiesComponent
        get() = PropertiesComponent.getInstance()

    fun persistAgents(agents: List<PersistedAgent>) {
        val json = JsonUtils.toJson(agents)
        projectProps.setValue(Constants.PROJECT_KEY_AGENTS, json)
    }

    fun restoreAgents(): List<PersistedAgent> {
        val json = projectProps.getValue(Constants.PROJECT_KEY_AGENTS) ?: return emptyList()
        return try {
            JsonUtils.fromJson<List<PersistedAgent>>(json)
        } catch (e: Exception) {
            log.warn("Failed to restore agents: ${e.message}")
            emptyList()
        }
    }

    fun saveAgentSeats(seats: Map<String, Any?>) {
        projectProps.setValue(Constants.PROJECT_KEY_AGENT_SEATS, JsonUtils.toJson(seats))
    }

    fun getAgentSeats(): Map<String, Any?> {
        val json = projectProps.getValue(Constants.PROJECT_KEY_AGENT_SEATS) ?: return emptyMap()
        return try {
            JsonUtils.fromJson(json)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    var soundEnabled: Boolean
        get() = appProps.getBoolean(Constants.GLOBAL_KEY_SOUND_ENABLED, true)
        set(value) = appProps.setValue(Constants.GLOBAL_KEY_SOUND_ENABLED, value, true)
}
