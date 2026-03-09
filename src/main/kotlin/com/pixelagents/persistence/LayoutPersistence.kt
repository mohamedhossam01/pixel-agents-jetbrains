package com.pixelagents.persistence

import com.intellij.openapi.diagnostic.Logger
import com.pixelagents.util.Constants
import com.pixelagents.util.JsonUtils
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = Logger.getInstance("com.pixelagents.persistence.LayoutPersistence")

fun getLayoutFilePath(): String {
    val home = System.getProperty("user.home")
    return File(home, "${Constants.LAYOUT_FILE_DIR}/${Constants.LAYOUT_FILE_NAME}").absolutePath
}

fun readLayoutFromFile(): Map<String, Any?>? {
    val file = File(getLayoutFilePath())
    return try {
        if (!file.exists()) null
        else JsonUtils.toMap(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        log.error("Failed to read layout file", e)
        null
    }
}

fun writeLayoutToFile(layout: Map<String, Any?>) {
    val filePath = getLayoutFilePath()
    val file = File(filePath)
    try {
        file.parentFile?.mkdirs()
        val json = JsonUtils.toPrettyJson(layout)
        val tmpFile = File("$filePath.tmp")
        tmpFile.writeText(json, Charsets.UTF_8)
        tmpFile.renameTo(file)
    } catch (e: Exception) {
        log.error("Failed to write layout file", e)
    }
}

fun loadLayout(defaultLayout: Map<String, Any?>?): Map<String, Any?>? {
    // 1. Try file
    val fromFile = readLayoutFromFile()
    if (fromFile != null) {
        log.info("Layout loaded from file")
        return fromFile
    }

    // 2. Use bundled default
    if (defaultLayout != null) {
        log.info("Writing bundled default layout to file")
        writeLayoutToFile(defaultLayout)
        return defaultLayout
    }

    return null
}

class LayoutWatcher(
    private val onExternalChange: (Map<String, Any?>) -> Unit,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pixel-agents-layout-watch").apply { isDaemon = true }
    }
    private val disposed = AtomicBoolean(false)
    private var skipNextChange = false
    private var lastMtime = 0L
    private var pollFuture: ScheduledFuture<*>? = null
    private var watchThread: Thread? = null

    init {
        val file = File(getLayoutFilePath())
        if (file.exists()) {
            lastMtime = file.lastModified()
        }

        // Start NIO watch
        val parent = file.parentFile
        if (parent != null && parent.exists()) {
            watchThread = Thread({
                try {
                    val ws = FileSystems.getDefault().newWatchService()
                    parent.toPath().register(ws, StandardWatchEventKinds.ENTRY_MODIFY)
                    while (!Thread.currentThread().isInterrupted && !disposed.get()) {
                        val key = ws.poll(2, TimeUnit.SECONDS) ?: continue
                        for (event in key.pollEvents()) {
                            val changed = event.context() as? Path ?: continue
                            if (changed.toString() == Constants.LAYOUT_FILE_NAME) {
                                checkForChange()
                            }
                        }
                        key.reset()
                    }
                    ws.close()
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    log.info("Layout watch error: $e")
                }
            }, "pixel-agents-layout-nio").apply { isDaemon = true }
            watchThread?.start()
        }

        // Polling backup
        pollFuture = scheduler.scheduleAtFixedRate({
            if (!disposed.get()) checkForChange()
        }, Constants.LAYOUT_FILE_POLL_INTERVAL_MS, Constants.LAYOUT_FILE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun checkForChange() {
        if (disposed.get()) return
        try {
            val file = File(getLayoutFilePath())
            if (!file.exists()) return
            val mtime = file.lastModified()
            if (mtime <= lastMtime) return
            lastMtime = mtime

            if (skipNextChange) {
                skipNextChange = false
                return
            }

            val raw = file.readText(Charsets.UTF_8)
            val layout = JsonUtils.toMap(raw)
            log.info("External layout change detected")
            onExternalChange(layout)
        } catch (e: Exception) {
            log.error("Error checking layout file", e)
        }
    }

    @Synchronized
    fun markOwnWrite() {
        skipNextChange = true
        try {
            val file = File(getLayoutFilePath())
            if (file.exists()) {
                lastMtime = file.lastModified()
            }
        } catch (_: Exception) {}
    }

    fun dispose() {
        disposed.set(true)
        watchThread?.interrupt()
        pollFuture?.cancel(false)
        scheduler.shutdownNow()
    }
}
