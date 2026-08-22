package com.example.idupi.domain.model

/**
 * The largest growth still treated as "something arrived while reading".
 *
 * One turn adds its parts one frame at a time -- the message, a tool card, the
 * answer -- so its steps are small. A whole history landing at once is not a
 * step, it is a different list.
 */
private const val MAX_ANIMATED_GROWTH = 3

/**
 * Whether the chat should ANIMATE its scroll to the last message, or jump.
 *
 * Opening a session replaced the whole list and then animated to the end, so a
 * 50-message session travelled visibly through all 50 -- and a session with
 * hundreds took long enough that the user sat waiting for it to arrive. The
 * animation is worth keeping for what it was written for: a message arriving
 * while the user is reading, where the movement is what shows something was
 * added. Loading a history is not that, so it jumps.
 */
fun shouldAnimateScroll(previousSize: Int, newSize: Int): Boolean {
    if (newSize <= 0) return false          // nothing to scroll to
    if (previousSize <= 0) return false     // first paint: be there already
    if (newSize < previousSize) return false // replaced or cleared, not grown
    return newSize - previousSize <= MAX_ANIMATED_GROWTH
}
