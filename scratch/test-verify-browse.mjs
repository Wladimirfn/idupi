import http from "node:http";
import { dirname, join } from "node:path";
import { readdirSync, existsSync } from "node:fs";
import { homedir } from "node:os";

// Import directly from the updated index.mjs functions or test logic
function getAvailableDrives() {
    const drives = [];
    const letters = "CDEFGHIJKLMNOPQRSTUVWXYZ".split("");
    for (const letter of letters) {
        const driveRoot = `${letter}:\\`;
        try {
            if (existsSync(driveRoot)) {
                drives.push({ name: `Disco (${letter}:)`, path: driveRoot, isProject: false });
            }
        } catch (e) {}
    }
    return drives;
}

function browseFileSystem(targetPath) {
    if (!targetPath || !targetPath.trim()) {
        return {
            currentPath: "",
            parentPath: null,
            shortcuts: [],
            directories: getAvailableDrives()
        };
    }

    const norm = targetPath.trim().replace(/[/\\]+$/, "") || targetPath.trim();
    let parent = dirname(norm);
    if (norm.length <= 3 && norm.includes(":")) {
        parent = "";
    } else if (parent === norm) {
        parent = "";
    }

    const directories = [];
    try {
        if (existsSync(norm)) {
            const entries = readdirSync(norm, { withFileTypes: true });
            for (const entry of entries) {
                if (entry.isDirectory()) {
                    if (entry.name.startsWith("$") || entry.name === "System Volume Information") continue;
                    directories.push({ name: entry.name, path: join(norm, entry.name), isProject: false });
                }
            }
        }
    } catch (e) {}

    return { currentPath: norm, parentPath: parent, directories };
}

console.log("Root browse:", browseFileSystem("").directories.length, "drives");
console.log("C:\\ browse:", browseFileSystem("C:\\").directories.length, "directories");
console.log("C:\\Users browse:", browseFileSystem("C:\\Users").directories.length, "directories");
