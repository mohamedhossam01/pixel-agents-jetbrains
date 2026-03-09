package com.pixelagents.assets

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.pixelagents.jcef.MessageBridge
import com.pixelagents.util.Constants
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private val log = Logger.getInstance("com.pixelagents.assets.AssetLoader")

typealias SpriteData = List<List<String>>

/**
 * Loads all assets from the JAR classpath (/webview-dist/assets/).
 * PNGs are converted to SpriteData (2D hex string arrays) same as VS Code version.
 */
class AssetLoader {

    fun loadAndSendAssets(bridge: MessageBridge) {
        try {
            loadAndSendCharacterSprites(bridge)
            loadAndSendFloorTiles(bridge)
            loadAndSendWallTiles(bridge)
            loadAndSendFurnitureAssets(bridge)
        } catch (e: Exception) {
            log.error("Error loading assets", e)
        }
    }

    private fun loadAndSendCharacterSprites(bridge: MessageBridge) {
        val characters = mutableListOf<Map<String, List<SpriteData>>>()

        for (ci in 0 until Constants.CHAR_COUNT) {
            val stream = javaClass.getResourceAsStream("/webview-dist/assets/characters/char_$ci.png")
            if (stream == null) {
                log.info("No character sprite found: char_$ci.png")
                return
            }
            val img = ImageIO.read(stream)
            stream.close()

            val charData = mutableMapOf<String, List<SpriteData>>()
            for ((dirIdx, dir) in Constants.CHARACTER_DIRECTIONS.withIndex()) {
                val rowOffsetY = dirIdx * Constants.CHAR_FRAME_H
                val frames = mutableListOf<SpriteData>()
                for (f in 0 until Constants.CHAR_FRAMES_PER_ROW) {
                    val frameOffsetX = f * Constants.CHAR_FRAME_W
                    frames.add(extractSprite(img, frameOffsetX, rowOffsetY, Constants.CHAR_FRAME_W, Constants.CHAR_FRAME_H))
                }
                charData[dir] = frames
            }
            characters.add(charData)
        }

        log.info("Loaded ${characters.size} character sprites")
        bridge.postMessage("characterSpritesLoaded", "characters" to characters)
    }

    private fun loadAndSendFloorTiles(bridge: MessageBridge) {
        val stream = javaClass.getResourceAsStream("/webview-dist/assets/floors.png")
        if (stream == null) {
            log.info("No floors.png found")
            return
        }
        val img = ImageIO.read(stream)
        stream.close()

        val sprites = mutableListOf<SpriteData>()
        for (t in 0 until Constants.FLOOR_PATTERN_COUNT) {
            sprites.add(extractSprite(img, t * Constants.FLOOR_TILE_SIZE, 0, Constants.FLOOR_TILE_SIZE, Constants.FLOOR_TILE_SIZE))
        }

        log.info("Loaded ${sprites.size} floor tile patterns")
        bridge.postMessage("floorTilesLoaded", "sprites" to sprites)
    }

    private fun loadAndSendWallTiles(bridge: MessageBridge) {
        val stream = javaClass.getResourceAsStream("/webview-dist/assets/walls.png")
        if (stream == null) {
            log.info("No walls.png found")
            return
        }
        val img = ImageIO.read(stream)
        stream.close()

        val sprites = mutableListOf<SpriteData>()
        for (mask in 0 until Constants.WALL_BITMASK_COUNT) {
            val ox = (mask % Constants.WALL_GRID_COLS) * Constants.WALL_PIECE_WIDTH
            val oy = (mask / Constants.WALL_GRID_COLS) * Constants.WALL_PIECE_HEIGHT
            sprites.add(extractSprite(img, ox, oy, Constants.WALL_PIECE_WIDTH, Constants.WALL_PIECE_HEIGHT))
        }

        log.info("Loaded ${sprites.size} wall tile pieces")
        bridge.postMessage("wallTilesLoaded", "sprites" to sprites)
    }

    private fun loadAndSendFurnitureAssets(bridge: MessageBridge) {
        val catalogStream = javaClass.getResourceAsStream("/webview-dist/assets/furniture/furniture-catalog.json")
        if (catalogStream == null) {
            log.info("No furniture-catalog.json found")
            return
        }

        val catalogJson = catalogStream.bufferedReader().readText()
        catalogStream.close()
        val catalogObj = JsonParser.parseString(catalogJson).asJsonObject
        val assetsArray = catalogObj.getAsJsonArray("assets") ?: return

        val catalog = mutableListOf<Map<String, Any?>>()
        val sprites = mutableMapOf<String, SpriteData>()

        for (element in assetsArray) {
            val asset = element.asJsonObject
            val id = asset.get("id").asString
            val width = asset.get("width").asInt
            val height = asset.get("height").asInt

            var filePath = asset.get("file").asString
            if (!filePath.startsWith("assets/")) {
                filePath = "assets/$filePath"
            }

            val entry = mutableMapOf<String, Any?>(
                "id" to id,
                "name" to asset.get("name").asString,
                "label" to asset.get("label").asString,
                "category" to asset.get("category").asString,
                "file" to asset.get("file").asString,
                "width" to width,
                "height" to height,
                "footprintW" to asset.get("footprintW").asInt,
                "footprintH" to asset.get("footprintH").asInt,
                "isDesk" to asset.get("isDesk").asBoolean,
                "canPlaceOnWalls" to asset.get("canPlaceOnWalls").asBoolean,
            )
            asset.get("partOfGroup")?.let { if (!it.isJsonNull) entry["partOfGroup"] = it.asBoolean }
            asset.get("groupId")?.let { if (!it.isJsonNull) entry["groupId"] = it.asString }
            asset.get("canPlaceOnSurfaces")?.let { if (!it.isJsonNull) entry["canPlaceOnSurfaces"] = it.asBoolean }
            asset.get("backgroundTiles")?.let { if (!it.isJsonNull) entry["backgroundTiles"] = it.asInt }
            asset.get("orientation")?.let { if (!it.isJsonNull) entry["orientation"] = it.asString }
            asset.get("state")?.let { if (!it.isJsonNull) entry["state"] = it.asString }
            asset.get("surfaceCells")?.let { if (!it.isJsonNull) {
                val cells = it.asJsonArray.map { cell ->
                    val arr = cell.asJsonArray
                    listOf(arr[0].asInt, arr[1].asInt)
                }
                entry["surfaceCells"] = cells
            }}

            catalog.add(entry)

            // Load sprite PNG
            val spriteStream = javaClass.getResourceAsStream("/webview-dist/$filePath")
            if (spriteStream != null) {
                try {
                    val img = ImageIO.read(spriteStream)
                    spriteStream.close()
                    sprites[id] = extractSprite(img, 0, 0, width, height)
                } catch (e: Exception) {
                    log.warn("Error loading sprite for $id: ${e.message}")
                }
            } else {
                log.warn("Sprite file not found: $filePath")
            }
        }

        log.info("Loaded ${sprites.size}/${catalog.size} furniture assets")
        bridge.postMessage(
            mapOf(
                "type" to "furnitureAssetsLoaded",
                "catalog" to catalog,
                "sprites" to sprites,
            )
        )
    }

    private fun extractSprite(img: BufferedImage, ox: Int, oy: Int, width: Int, height: Int): SpriteData {
        val sprite = mutableListOf<List<String>>()
        for (y in 0 until height) {
            val row = mutableListOf<String>()
            for (x in 0 until width) {
                val px = ox + x
                val py = oy + y
                if (px >= img.width || py >= img.height) {
                    row.add("")
                    continue
                }
                val argb = img.getRGB(px, py)
                val a = (argb shr 24) and 0xFF
                if (a < Constants.PNG_ALPHA_THRESHOLD) {
                    row.add("")
                } else {
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    row.add("#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}".uppercase())
                }
            }
            sprite.add(row)
        }
        return sprite
    }

    fun loadDefaultLayout(): Map<String, Any?>? {
        val stream = javaClass.getResourceAsStream("/webview-dist/assets/default-layout.json") ?: return null
        return try {
            val json = stream.bufferedReader().readText()
            stream.close()
            com.pixelagents.util.JsonUtils.toMap(json)
        } catch (e: Exception) {
            log.warn("Failed to load default layout: ${e.message}")
            null
        }
    }
}
