package com.pixelagents.jcef

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

private val log = Logger.getInstance("com.pixelagents.jcef.WebviewServer")

/**
 * Embedded HTTP server that serves webview-dist resources on localhost.
 * This avoids all JCEF issues with file://, data: URLs, loadHTML size limits,
 * and custom scheme handlers.
 */
class WebviewServer {
    private var server: HttpServer? = null
    private var tempDir: File? = null
    var port: Int = 0
        private set

    fun start(): String {
        val dir = extractResources()
        tempDir = dir

        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { exchange ->
            var path = exchange.requestURI.path
            if (path == "/") path = "/index.html"

            val file = File(dir, path.removePrefix("/"))
            if (file.exists() && file.isFile && file.canonicalPath.startsWith(dir.canonicalPath)) {
                val bytes = file.readBytes()
                val mime = getMimeType(file.name)
                exchange.responseHeaders.add("Content-Type", mime)
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val msg = "Not found: $path"
                exchange.sendResponseHeaders(404, msg.length.toLong())
                exchange.responseBody.use { it.write(msg.toByteArray()) }
            }
        }
        srv.executor = null
        srv.start()
        server = srv
        port = srv.address.port
        log.info("Webview server started on http://127.0.0.1:$port")
        return "http://127.0.0.1:$port/index.html"
    }

    fun stop() {
        server?.stop(0)
        server = null
        tempDir?.let { dir ->
            try {
                dir.deleteRecursively()
            } catch (_: Exception) {}
        }
        tempDir = null
    }

    private fun extractResources(): File {
        val dir = Files.createTempDirectory("pixel-agents-webview").toFile()
        val cl = WebviewServer::class.java.classLoader

        // Read index.html to discover asset references
        val indexHtml = cl.getResourceAsStream("webview-dist/index.html")
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("webview-dist/index.html not found in JAR")

        File(dir, "index.html").writeText(indexHtml)

        // Extract all known asset patterns from index.html
        val assetRefs = Regex("""(?:src|href)="\./(assets/[^"]+)"""")
            .findAll(indexHtml)
            .map { it.groupValues[1] }
            .toList()

        for (ref in assetRefs) {
            extractResource(cl, "webview-dist/$ref", File(dir, ref))
        }

        // Also extract known assets that might be referenced from CSS (font file, etc.)
        extractResourcesByPrefix(cl, dir)

        log.info("Extracted webview resources to $dir (${dir.listFiles()?.size ?: 0} top-level entries)")
        return dir
    }

    private fun extractResourcesByPrefix(cl: ClassLoader, dir: File) {
        // Extract all files from webview-dist/assets/ that we know about
        // We scan for common patterns since we can't list classpath resources easily
        val knownExtensions = listOf("ttf", "woff", "woff2", "png", "json", "svg")

        // Try to read the CSS to find font references
        val assetsDir = File(dir, "assets")
        if (assetsDir.exists()) {
            for (cssFile in assetsDir.listFiles { f -> f.extension == "css" } ?: emptyArray()) {
                val css = cssFile.readText()
                val fontRefs = Regex("""url\(\.?/?([^)]+)\)""").findAll(css)
                for (match in fontRefs) {
                    val ref = match.groupValues[1]
                    val resourcePath = "webview-dist/assets/$ref"
                    val target = File(assetsDir, ref)
                    if (!target.exists()) {
                        extractResource(cl, resourcePath, target)
                    }
                }
            }
        }

        // Extract character sprites and other known assets
        val additionalAssets = listOf(
            "assets/characters/char_0.png",
            "assets/characters/char_1.png",
            "assets/characters/char_2.png",
            "assets/characters/char_3.png",
            "assets/characters/char_4.png",
            "assets/characters/char_5.png",
            "assets/walls.png",
            "assets/default-layout.json",
            "assets/furniture/furniture-catalog.json",
            "assets/furniture/BED_1.png",
            "assets/furniture/BED_2.png",
            "assets/furniture/BLUE_SPELL_BOOK.png",
            "assets/furniture/BOOKSHELF_TALL.png",
            "assets/furniture/BOOK_ON_TABLE.png",
            "assets/furniture/BROOM.png",
            "assets/furniture/BUSH.png",
            "assets/furniture/BUSH_ALT.png",
            "assets/furniture/CARDBOARD.png",
            "assets/furniture/CARDBOARD_SMALL.png",
            "assets/furniture/CAULDRON.png",
            "assets/furniture/DOOR_MAT.png",
            "assets/furniture/HERB_PLANT.png",
            "assets/furniture/HERB_PLANT_ALT.png",
            "assets/furniture/LONG_DESK.png",
            "assets/furniture/MAGIC_RUG.png",
            "assets/furniture/MIRROR.png",
            "assets/furniture/POTIONSHELF_TALL.png",
            "assets/furniture/POTION_BLUE.png",
            "assets/furniture/POTION_BLUE_2.png",
            "assets/furniture/POTION_BLUE_3.png",
            "assets/furniture/POTION_GREEN.png",
            "assets/furniture/POTION_GREEN_2.png",
            "assets/furniture/POTION_GREEN_3.png",
            "assets/furniture/POTION_PURPLE.png",
            "assets/furniture/POTION_PURPLE_2.png",
            "assets/furniture/POTION_PURPLE_3.png",
            "assets/furniture/POTION_RED.png",
            "assets/furniture/POTION_RED_2.png",
            "assets/furniture/POTION_RED_3.png",
            "assets/furniture/POTION_YELLOW.png",
            "assets/furniture/POTION_YELLOW_2.png",
            "assets/furniture/POTION_YELLOW_3.png",
            "assets/furniture/PURPLE_SPELL_BOOK.png",
            "assets/furniture/STOOL.png",
            "assets/furniture/TABLE.png",
            "assets/furniture/VASE_1.png",
            "assets/furniture/VASE_2.png",
            "assets/furniture/VASE_3.png",
            "assets/furniture/WALL_SHELF_A.png",
            "assets/furniture/WALL_SHELF_B.png",
            "assets/furniture/WINDOW.png",
            "assets/furniture/YELLOW_SPELL_BOOK.png",
        )
        for (asset in additionalAssets) {
            extractResource(cl, "webview-dist/$asset", File(dir, asset))
        }
    }

    private fun extractResource(cl: ClassLoader, resourcePath: String, target: File) {
        val stream = cl.getResourceAsStream(resourcePath) ?: return
        target.parentFile?.mkdirs()
        stream.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun getMimeType(name: String): String {
        return when (name.substringAfterLast('.').lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
