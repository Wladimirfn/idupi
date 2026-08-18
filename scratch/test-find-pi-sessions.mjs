import fs from "node:fs";
import path from "node:path";
import os from "node:os";

const base = path.join(os.homedir(), ".pi", "agent", "sessions");
console.log("Sessions base dir:", base);

const subdirs = fs.readdirSync(base);
console.log("Total subdirectories in ~/.pi/agent/sessions:", subdirs.length);

for (const sd of subdirs) {
    const dirPath = path.join(base, sd);
    if (fs.statSync(dirPath).isDirectory()) {
        const files = fs.readdirSync(dirPath).filter(f => f.endsWith(".jsonl"));
        if (files.length > 0) {
            const firstFile = path.join(dirPath, files[0]);
            const firstLine = fs.readFileSync(firstFile, "utf8").split("\n")[0];
            try {
                const meta = JSON.parse(firstLine);
                console.log(`Directory: [${sd}] -> ${files.length} sessions, CWD: [${meta.cwd}]`);
            } catch (e) {
                console.log(`Directory: [${sd}] -> ${files.length} sessions, cannot parse first line`);
            }
        }
    }
}
