package com.pixelagents.watcher

import com.intellij.openapi.diagnostic.Logger
import com.pixelagents.jcef.MessageBridge
import com.pixelagents.model.AgentState
import com.pixelagents.parser.TimerManager
import com.pixelagents.parser.processTranscriptLine
import com.pixelagents.util.Constants
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.*
import java.util.concurrent.*

private val log = Logger.getInstance("com.pixelagents.watcher.JsonlFileWatcher")

class JsonlFileWatcher(
    private val agents: ConcurrentHashMap<Int, AgentState>,
    private val timerManager: TimerManager,
    private val bridge: MessageBridge,
) {
    private val scheduler = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "pixel-agents-watcher").apply { isDaemon = true }
    }
    private val watchServices = ConcurrentHashMap<Int, WatchService>()
    private val pollingTimers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private val watchThreads = ConcurrentHashMap<Int, Thread>()

    fun startWatching(agentId: Int, filePath: String) {
        val file = File(filePath)
        val parent = file.parentFile ?: return

        // Primary: NIO WatchService
        try {
            val watchService = FileSystems.getDefault().newWatchService()
            parent.toPath().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
            watchServices[agentId] = watchService

            val thread = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val key = watchService.poll(2, TimeUnit.SECONDS) ?: continue
                        for (event in key.pollEvents()) {
                            val changed = event.context() as? Path ?: continue
                            if (changed.toString() == file.name) {
                                readNewLines(agentId)
                            }
                        }
                        key.reset()
                    }
                } catch (_: InterruptedException) {
                    // Normal shutdown
                } catch (_: ClosedWatchServiceException) {
                    // Normal shutdown
                }
            }, "pixel-agents-watch-$agentId").apply { isDaemon = true }
            thread.start()
            watchThreads[agentId] = thread
        } catch (e: Exception) {
            log.info("WatchService failed for agent $agentId: $e")
        }

        // Secondary: polling fallback
        val future = scheduler.scheduleAtFixedRate({
            if (!agents.containsKey(agentId)) {
                stopWatching(agentId)
                return@scheduleAtFixedRate
            }
            readNewLines(agentId)
        }, Constants.FILE_WATCHER_POLL_INTERVAL_MS, Constants.FILE_WATCHER_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        pollingTimers[agentId] = future
    }

    fun readNewLines(agentId: Int) {
        val agent = agents[agentId] ?: return
        try {
            val file = File(agent.jsonlFile)
            if (!file.exists()) return
            val size = file.length()
            if (size <= agent.fileOffset) return

            val bytesToRead = (size - agent.fileOffset).toInt()
            val buf = ByteArray(bytesToRead)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(agent.fileOffset)
                raf.readFully(buf)
            }
            agent.fileOffset = size

            val text = agent.lineBuffer + String(buf, Charsets.UTF_8)
            val lines = text.split("\n")
            agent.lineBuffer = lines.last()
            val completeLines = lines.dropLast(1)

            val hasLines = completeLines.any { it.isNotBlank() }
            if (hasLines) {
                timerManager.cancelWaitingTimer(agentId)
                timerManager.cancelPermissionTimer(agentId)
                if (agent.permissionSent) {
                    agent.permissionSent = false
                    bridge.postMessage("agentToolPermissionClear", "id" to agentId)
                }
            }

            for (line in completeLines) {
                if (line.isBlank()) continue
                processTranscriptLine(agentId, line, agents, timerManager, bridge)
            }
        } catch (e: Exception) {
            log.info("Read error for agent $agentId: $e")
        }
    }

    fun stopWatching(agentId: Int) {
        watchThreads.remove(agentId)?.interrupt()
        watchServices.remove(agentId)?.close()
        pollingTimers.remove(agentId)?.cancel(false)
    }

    fun dispose() {
        for (id in watchServices.keys.toList()) {
            stopWatching(id)
        }
        for (id in pollingTimers.keys.toList()) {
            pollingTimers.remove(id)?.cancel(false)
        }
        scheduler.shutdownNow()
    }
}
