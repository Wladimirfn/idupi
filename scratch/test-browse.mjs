import { readdirSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join, dirname } from "node:path";

function normalizeBrowsePath(targetPath) {
    if (!targetPath || !targetPath.trim()) return "";
    let trimmed = targetPath.trim();
    // Check if it's a Windows drive like "C:", "C:\", "c:/"
    if (/^[a-zA-Z]:[/\\]?$/.test(trimmed)) {
        return trimmed.slice(0, 1).toUpperCase() + ":\\";
    }
    // Normal directory path: remove trailing slashes
    trimmed = trimmed.replace(/[/\\]+$/, "");
    return trimmed;
}

function getParentBrowsePath(normPath) {
    if (!normPath) return null;
    // If it's a drive root like "C:\", the parent is the drives list (empty string)
    if (/^[a-zA-Z]:\\?$/.test(normPath)) {
        return "";
    }
    let parent = dirname(normPath);
    // If dirname returned "C:" or "C:\", normalize to "C:\"
    if (/^[a-zA-Z]:\\?$/.test(parent)) {
        return parent.slice(0, 1).toUpperCase() + ":\\";
    }
    if (parent === normPath) {
        return "";
    }
    return parent;
}

function browseFileSystem(targetPath) {
    const norm = normalizeBrowsePath(targetPath);
    if (!norm) {
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
        return { currentPath: "", parentPath: null, directories: drives };
    }

    const parent = getParentBrowsePath(norm);
    const directories = [];

    try {
        if (existsSync(norm)) {
            const entries = readdirSync(norm, { withFileTypes: true });
            for (const entry of entries) {
                if (entry.isDirectory()) {
                    const name = entry.name;
                    // Ignore system/hidden directories
                    if (name.startsWith("$") || name.startsWith(".") || name === "System Volume Information" || name === "Recovery") {
                        continue;
                    }
                    const fullP = join(norm, name);
                    let isProj = false;
                    let projType = null;
                    try {
                        if (existsSync(join(fullP, ".git"))) { isProj = true; projType = "Git"; }
                        else if (existsSync(join(fullP, "package.json"))) { isProj = true; projType = "Node.js"; }
                        else if (existsSync(join(fullP, "build.gradle")) || existsSync(join(fullP, "build.gradle.kts"))) { isProj = true; projType = "Android / Gradle"; }
                        else if (existsSync(join(fullP, "requirements.txt")) || existsSync(join(fullP, "pyproject.toml"))) { isProj = true; projType = "Python"; }
                    } catch (e) {}

                    directories.push({
                        name: name,
                        path: fullP,
                        isProject: isProj,
                        projectType: projType
                    });
                }
            }
        }
    } catch (e) {
        console.error("[Browse Error]", e.message);
    }

    return {
        currentPath: norm,
        parentPath: parent,
        directories: directories.sort((a, b) => (b.isProject ? 1 : 0) - (a.isProject ? 1 : 0) || a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }))
    };
}

console.log("Empty path ->", browseFileSystem(""));
console.log("C:\\ path ->", browseFileSystem("C:\\"));
console.log("C: path ->", browseFileSystem("C:"));
console.log("C:\\Users path ->", browseFileSystem("C:\\Users"));
console.log("Parent of C:\\Users ->", getParentBrowsePath("C:\\Users"));
console.log("Parent of C:\\ ->", getParentBrowsePath("C:\\"));
