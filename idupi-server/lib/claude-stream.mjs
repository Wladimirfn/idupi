/**
 * Splits one Claude `assistant` stream event into the chat events it stands for.
 *
 * A Claude turn holds SEVERAL assistant messages: the preamble written before
 * reaching for tools, then the real answer after their results come back. Each
 * `assistant` event is ONE complete message -- not a fragment -- but the
 * handler published every one of them as a plain text delta with no boundary
 * between them.
 *
 * The app keeps a single ordered list and appends a delta to whichever bubble
 * is still streaming, so with no boundary the first message opened a bubble and
 * every later message was written back into it -- above the tool and subagent
 * cards that had been appended below in the meantime. The final answer ended up
 * sitting on top of the work it came after, which is exactly the chronology
 * that was reported broken. Pi already closes each assistant message where Pi
 * itself ends it (see the message_end handler); this is the same boundary for
 * Claude.
 *
 * Order inside one message: the text is closed BEFORE the tool calls of that
 * same message open their cards. Claude emits text blocks ahead of `tool_use`
 * blocks, and regardless of block order the whole message was composed before
 * any of its tools ran, so its text belongs above them.
 */

/**
 * @param {Array<object>} content the `content` array of an `assistant` event
 * @returns {{text: string|null, tools: Array<{id: string, name: string, input: object}>}}
 *   `text` is the message's complete text (null when it carried none), and
 *   `tools` are its tool calls in the order Claude listed them.
 */
export function planAssistantMessage(content) {
    if (!Array.isArray(content)) return { text: null, tools: [] };

    const chunks = [];
    const tools = [];
    for (const item of content) {
        if (!item || typeof item !== "object") continue;
        if (item.type === "text" && typeof item.text === "string" && item.text) {
            chunks.push(item.text);
        } else if (item.type === "tool_use") {
            tools.push({
                id: item.id || `tool-${Date.now()}`,
                name: item.name || "Tool",
                input: item.input || {},
                item,
            });
        }
    }

    const text = chunks.join("");
    return { text: text.trim() ? text : null, tools };
}
