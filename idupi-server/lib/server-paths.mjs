import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Anchored to this file, never to process.cwd(): start-server.ps1 launches the
// server from idupi-server/, while tests and manual runs start from the repo
// root. Paths derived from the working directory silently pointed at
// idupi-server/idupi-server/projects.json and made the default project the
// server folder instead of the repository.

/** Absolute path of the idupi-server directory. */
export const SERVER_DIR = dirname(dirname(fileURLToPath(import.meta.url)));

/** Absolute path of the repository root (parent of idupi-server). */
export const REPO_ROOT = dirname(SERVER_DIR);

/** Persisted project registry (gitignored). */
export const PROJECTS_JSON_FILE = join(SERVER_DIR, "projects.json");
