import { join, basename } from "node:path";
import { homedir } from "node:os";
import { readdirSync, statSync, readFileSync, existsSync } from "node:fs";

function testScan(projPath, projName, engineFilter = "all") {
    const normProjPath = projPath.toLowerCase().replace(/\\/g, "/");
    const projBaseName = basename(projPath).toLowerCase();
    console.log("normProjPath:", normProjPath);
    console.log("projBaseName:", projBaseName);

    const allSessions = [];

    // 1. Pi CLI
    if (engineFilter === "all" || engineFilter === "pi-cli") {
        const sessionsBaseDir = join(homedir(), ".pi", "agent", "sessions");
        if (existsSync(sessionsBaseDir)) {
            const subdirs = readdirSync(sessionsBaseDir);
            for (const subdir of subdirs) {
                const fullDir = join(sessionsBaseDir, subdir);
                if (!statSync(fullDir).isDirectory()) continue;

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
                        const match = metaCwd === normProjPath || 
                                      metaCwd.endsWith("/" + projBaseName) || 
                                      normProjPath.endsWith("/" + basename(metaCwd)) ||
                                      subdir.toLowerCase().includes(projBaseName);

                        if (match) {
                            allSessions.push({ id: meta.id, engine: "pi-cli", cwd: metaCwd, file });
                        }
                    } catch (e) {}
                }
            }
        }
    }

    // 3. Claude
    if (engineFilter === "all" || engineFilter === "claude") {
        const claudeProjectsDir = join(homedir(), ".claude", "projects");
        if (existsSync(claudeProjectsDir)) {
            const subdirs = readdirSync(claudeProjectsDir);
            console.log("Claude project subdirs:", subdirs);
            for (const subdir of subdirs) {
                // Check if subdir matches project
                const subNorm = subdir.toLowerCase().replace(/[^a-z0-9]/g, "-");
                const pNorm = projPath.toLowerCase().replace(/[^a-z0-9]/g, "-");
                const bNorm = projBaseName.toLowerCase().replace(/[^a-z0-9]/g, "-");

                const match = subNorm === pNorm || subNorm.includes(bNorm) || pNorm.includes(subNorm);
                console.log(`Matching claude dir '${subdir}' against '${projBaseName}': match =`, match);

                if (match) {
                    const targetDir = join(claudeProjectsDir, subdir);
                    const files = readdirSync(targetDir).filter(f => f.endsWith(".jsonl"));
                    for (const f of files) {
                        allSessions.push({ id: basename(f, ".jsonl"), engine: "claude", subdir, file: f });
                    }
                }
            }
        }
    }

    console.log(`Total sessions found for '${projName}':`, allSessions.length);
    console.log(`Pi CLI:`, allSessions.filter(s => s.engine === "pi-cli").length);
    console.log(`Claude:`, allSessions.filter(s => s.engine === "claude").length);
}

testScan(join(homedir(), "OneDrive", "Escritorio", "Mis proyectos", "Sistema_de_mantencion"), "Sistema_de_mantencion");
