import fs from "node:fs";
import path from "node:path";
import os from "node:os";

function getTestPiSessions(projPath, projName) {
    const sessionsBaseDir = path.join(os.homedir(), ".pi", "agent", "sessions");
    if (!fs.existsSync(sessionsBaseDir)) return [];

    const normProjPath = projPath.toLowerCase().replace(/\\/g, "/");
    const projBaseName = path.basename(projPath).toLowerCase();

    const subdirs = fs.readdirSync(sessionsBaseDir);
    const sessions = [];

    for (const subdir of subdirs) {
        const fullDir = path.join(sessionsBaseDir, subdir);
        if (!fs.statSync(fullDir).isDirectory()) continue;

        const files = fs.readdirSync(fullDir).filter(f => f.endsWith(".jsonl"));
        for (const file of files) {
            const fullFile = path.join(fullDir, file);
            try {
                const content = fs.readFileSync(fullFile, "utf8");
                const lines = content.split("\n").filter(l => l.trim());
                if (lines.length === 0) continue;

                const meta = JSON.parse(lines[0]);
                if (meta.type !== "session") continue;

                const metaCwd = (meta.cwd || "").toLowerCase().replace(/\\/g, "/");
                const match = metaCwd === normProjPath || 
                              metaCwd.endsWith("/" + projBaseName) || 
                              normProjPath.endsWith("/" + path.basename(metaCwd)) ||
                              subdir.toLowerCase().includes(projBaseName);

                if (match) {
                    let firstPrompt = "";
                    let lastReply = "";
                    let msgCount = 0;

                    for (let i = 1; i < lines.length; i++) {
                        try {
                            const entry = JSON.parse(lines[i]);
                            if (entry.type === "message") {
                                msgCount++;
                                if (entry.message?.role === "user" && !firstPrompt) {
                                    const txt = entry.message.content?.find(c => c.type === "text")?.text;
                                    if (txt) firstPrompt = txt;
                                }
                                if (entry.message?.role === "assistant") {
                                    const txt = entry.message.content?.find(c => c.type === "text")?.text;
                                    if (txt) lastReply = txt;
                                }
                            }
                        } catch (e) {}
                    }

                    const sid = meta.id || path.basename(file, ".jsonl");
                    const rawTime = meta.timestamp || fs.statSync(fullFile).mtime.toISOString();

                    sessions.push({
                        id: sid,
                        title: (firstPrompt || "Sesión Pi CLI").slice(0, 45),
                        project: projName,
                        date: new Date(rawTime).toLocaleString("es-ES"),
                        messageCount: msgCount,
                        preview: (lastReply || firstPrompt || "Sesión Pi").slice(0, 80),
                        engine: "pi-cli",
                        rawTimestamp: new Date(rawTime).getTime()
                    });
                }
            } catch (e) {}
        }
    }

    sessions.sort((a, b) => b.rawTimestamp - a.rawTimestamp);
    return sessions;
}

const idupiSessions = getTestPiSessions(path.join(os.homedir(), "AndroidStudioProjects", "IDUPI"), "IDUPI");
console.log("Found IDUPI Pi sessions:", idupiSessions.length);
idupiSessions.forEach(s => console.log(" -", s.id, "|", s.date, "|", s.title));
