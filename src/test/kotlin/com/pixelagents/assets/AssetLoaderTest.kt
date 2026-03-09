package com.pixelagents.assets

import org.junit.Assert.*
import org.junit.Test

class AssetLoaderTest {

    @Test
    fun testSpriteDataFormat() {
        // Verify the SpriteData type alias works correctly
        val sprite: SpriteData = listOf(
            listOf("", "#FF0000", "#00FF00"),
            listOf("#0000FF", "", "#FFFFFF"),
        )

        assertEquals(2, sprite.size)
        assertEquals(3, sprite[0].size)
        assertEquals("", sprite[0][0]) // transparent
        assertEquals("#FF0000", sprite[0][1]) // red
        assertEquals("#0000FF", sprite[1][0]) // blue
    }

    @Test
    fun testAssetLoaderInstantiation() {
        val loader = AssetLoader()
        // Should not throw
        assertNotNull(loader)
    }

    @Test
    fun testDefaultLayoutLoading() {
        val loader = AssetLoader()
        // In test environment, resources may not be available
        // This just verifies the method doesn't crash
        val layout = loader.loadDefaultLayout()
        // May be null in test environment (no JAR resources)
        // Just verify it returns without exception
    }
}
