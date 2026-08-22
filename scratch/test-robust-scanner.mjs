import { join, basename } from "node:path";
import { homedir } from "node:os";
import { readdirSync, statSync, readFileSync, existsSync } from "node:fs";

function testFullScanner(projPath, projName) {
    const normProjPath = projPath.toLowerCase().replace(/\\/g, "/");
    const projBaseName = basename(projPath).toLowerCase();
    const normBase = projBaseName.replace(/[^a-z0-9]/g, "-");
    const normFull = normProjPath.replace(/[^a-z0-9]/g, "-");

    const allSessions = [];

    // 1. Pi CLI
    const sessionsBaseDir = join(homedir(), ".pi", "agent", "sessions");
    if (existsSync(sessionsBaseDir)) {
        const subdirs = readdirSync(sessionsBaseDir);
        for (const subdir of subdirs) {
            const fullDir = join(sessionsBaseDir, subdir);
            if (!statSync(fullDir).isDirectory()) continue;

            const normSubdir = subdir.toLowerCase().replace(/[^a-z0-9]/g, "-");
            const isSubdirMatch = normSubdir.includes(normBase) || normFull.includes(normSubdir);

            const files = readdirSync(fullDir).filter(f => f.endsWith(".jsonl"));
            for (const file of files) {
                const fullFile = join(fullDir, file);
                try {
                    const content = readFileSync(fullFile, "utf8");
                    const lines = content.split("\n").filter(l => l.trim());
                    if (lines.length === 0) continue;

                    const meta = JSON.parse(lines[0]);
                    if (meta.type !== "session") continue;

                    const metaCwd = (meta.cwd || "").toLowerCase().replace(/\\/g, "/");
                    const metaNorm = metaCwd.replace(/[^a-z0-9]/g, "-");

                    const match = isSubdirMatch ||
                                  metaCwd.includes(projBaseName) ||
                                  normProjPath.includes(basename(metaCwd).toLowerCase()) ||
                                  metaNorm.includes(normBase);

                    if (match) {
                        allSessions.push({ id: meta.id, engine: "pi-cli" });
                    }
                } catch (e) {}
            }
        }
    }

    // 2. Claude
    const claudeProjectsDir = join(homedir(), ".claude", "projects");
    if (existsSync(claudeProjectsDir)) {
        const subdirs = readdirSync(claudeProjectsDir);
        for (const subdir of subdirs) {
            const normSubdir = subdir.toLowerCase().replace(/[^a-z0-9]/g, "-");
            if (normSubdir.includes(normBase) || normFull.includes(normSubdir)) {
                const fullDir = join(claudeProjectsDir, subdir);
                if (!statSync(fullDir).isDirectory()) continue;
                const files = readdirSync(fullDir).filter(f => f.endsWith(".jsonl"));
                for (const file of files) {
                    allSessions.push({ id: basename(file, ".jsonl"), engine: "claude" });
                }
            }
        }
    }

    console.log(`Scan Results for '${projName}':`);
    console.log(`Total: ${allSessions.length}`);
    console.log(`Pi CLI: ${allSessions.filter(s => s.engine === "pi-cli").length}`);
    console.log(`Claude: ${allSessions.filter(s => s.engine === "claude").length}`);
}

testFullScanner(join(homedir(), "OneDrive", "Escritorio", "Mis proyectos", "Sistema_de_mantencion"), "Sistema_de_mantencion");
