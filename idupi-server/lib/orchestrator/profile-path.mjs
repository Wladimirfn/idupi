// Single owner of the on-disk location of custom SDD profiles.
//
// Profile ids arrive in request bodies from the phone. Every route that maps
// an id to `<profilesDir>/<id>.json` must go through profileFilePath(), which
// refuses anything outside a strict allowlist, so ids can never carry path
// separators or dot-dot segments out of the profiles directory.

import { homedir } from "node:os";
import { join } from "node:path";

// Same character set the Android dialog and the save route produce when they
// derive an id from a profile name: [a-z0-9_-], plus upper case for presets.
const PROFILE_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

export function isSafeProfileId(profileId) {
    return typeof profileId === "string" && PROFILE_ID_PATTERN.test(profileId);
}

export function profilesDir(home = homedir()) {
    return join(home, ".config", "opencode", "profiles");
}

/** Absolute path of a custom profile file, or null when the id is unsafe. */
export function profileFilePath(profileId, home = homedir()) {
    if (!isSafeProfileId(profileId)) return null;
    return join(profilesDir(home), `${profileId}.json`);
}
