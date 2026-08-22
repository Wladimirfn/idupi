/**
 * Where one assistant message ends, for a stream that does not say so.
 *
 * Pi marks it (`message_end`) and Claude's `assistant` event IS one whole
 * message, so both can be closed on a signal the CLI actually sends. OpenCode's
 * stream carries only `text` parts and `tool_use` parts -- there is no message
 * boundary in it, and the last time a boundary was assumed rather than observed
 * the delegation card hung for weeks. So this derives the boundary instead of
 * inventing an event:
 *
 *   A model that stops writing to call a tool has finished what it was saying.
 *   Text arriving after that tool belongs to a NEW message.
 *
 * That rule needs nothing the stream does not already show. It matters because
 * the app appends each frame to one ordered list and writes a text fragment into
 * whichever bubble is still open: with no boundary, the first bubble stayed open
 * for the whole turn and every later message was written back into it, above the
 * tool and subagent cards appended below in the meantime. The answer rendered on
 * top of the work it came after.
 */
export class MessageBoundary {
    constructor() {
        this._text = "";
    }

    /** @param {string} text a streamed fragment of the message being written */
    append(text) {
        if (typeof text === "string") this._text += text;
    }

    get pending() {
        return this._text.trim().length > 0;
    }

    /**
     * Ends the current message and hands back its text.
     * @returns {string|null} null when nothing was written since the last take,
     *   which is how a caller tells "message finished" from "no message here" --
     *   two tools in a row must not close an empty bubble between them.
     */
    take() {
        const text = this._text.trim();
        this._text = "";
        return text || null;
    }
}
