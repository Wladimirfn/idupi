package com.example.idupi.domain.model

/**
 * Dirty-tile client side (hito 8): geometry and payload slicing for "tiles"
 * frames. Mirrors the helper's Go maths exactly -- row-major index order,
 * clipped edge tiles -- and everything here is decidable without a screen.
 */

/** One tile's destination rectangle inside the cached frame. */
data class TilePlacement(
    val index: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

fun isTileFrame(meta: ScreenFrameMeta): Boolean = meta.type == "tiles"

/**
 * Maps a row-major tile index to its pixel rectangle, clipped to the frame
 * bounds so ragged edges stay inside the frame.
 */
fun tileRect(index: Int, w: Int, h: Int, tw: Int): TilePlacement {
    val cols = (w + tw - 1) / tw
    val left = (index % cols) * tw
    val top = (index / cols) * tw
    return TilePlacement(
        index = index,
        left = left,
        top = top,
        width = minOf(left + tw, w) - left,
        height = minOf(top + tw, h) - top,
    )
}

/**
 * Splits a tiles frame's concatenated JPEG payload into per-tile chunks at
 * the declared lengths, in declared order. Lengths beyond the payload are a
 * protocol violation and fail loudly rather than feeding truncated bytes to
 * the decoder.
 */
fun tileSlices(
    payload: ByteArray,
    tiles: List<ScreenTileRef>,
): List<Pair<ScreenTileRef, ByteArray>> {
    val out = ArrayList<Pair<ScreenTileRef, ByteArray>>(tiles.size)
    var offset = 0
    for (ref in tiles) {
        if (offset + ref.len > payload.size) {
            throw IllegalStateException("tile map declares ${ref.len}B at offset $offset but payload has ${payload.size}B")
        }
        out += ref to payload.copyOfRange(offset, offset + ref.len)
        offset += ref.len
    }
    return out
}
