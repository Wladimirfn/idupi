package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Dirty-tile frames (hito 8): the wire carries a tile MAP plus one
 * concatenated JPEG blob; the client must place each slice back onto its
 * rectangle of the cached frame. All geometry and slicing is decidable
 * without a screen -- mirroring the helper's Go maths exactly.
 */
class ScreenTilesTest {

    private fun meta(
        w: Int = 128,
        h: Int = 128,
        tw: Int = 64,
        vararg refs: ScreenTileRef,
    ) = ScreenFrameMeta(id = 1, w = w, h = h, tw = tw, th = tw, tiles = refs.toList())

    @Test
    fun `tile rects mirror the helper's row-major clipped grid`() {
        assertEquals(TilePlacement(0, 0, 0, 64, 64), tileRect(0, w = 128, h = 128, tw = 64))
        assertEquals(TilePlacement(3, 64, 64, 64, 64), tileRect(3, w = 128, h = 128, tw = 64))
        // Ragged edge: frame 100x65, last tile clips to 36x1.
        assertEquals(TilePlacement(3, 64, 64, 36, 1), tileRect(3, w = 100, h = 65, tw = 64))
    }

    @Test
    fun `slices split the concatenated payload at the declared lengths`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        val payload = a + b
        val slices = tileSlices(payload, listOf(ScreenTileRef(i = 0, len = 3), ScreenTileRef(i = 3, len = 2)))

        assertEquals(2, slices.size)
        assertEquals(ScreenTileRef(0, 3), slices[0].first)
        assertTrue(a.contentEquals(slices[0].second))
        assertEquals(ScreenTileRef(3, 2), slices[1].first)
        assertTrue(b.contentEquals(slices[1].second))
    }

    @Test
    fun `declared lengths beyond the payload are a loud protocol error`() {
        assertThrows(IllegalStateException::class.java) {
            tileSlices(byteArrayOf(1), listOf(ScreenTileRef(i = 0, len = 10)))
        }
    }

    @Test
    fun `an empty tile set yields an empty slice list`() {
        assertEquals(emptyList<Pair<ScreenTileRef, ByteArray>>(), tileSlices(ByteArray(0), emptyList()))
    }

    @Test
    fun `only type=tiles frames are tile frames`() {
        assertEquals(false, isTileFrame(meta(type = null)))
        assertEquals(true, isTileFrame(meta(type = "tiles")))
    }

    private fun meta(type: String?): ScreenFrameMeta =
        ScreenFrameMeta(id = 1, w = 128, h = 128, type = type)

    private fun assertTrue(condition: Boolean) {
        assert(condition)
    }
}
