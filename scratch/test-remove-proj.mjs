import http from "node:http";

import { readFileSync } from "node:fs";

// Read the live token instead of carrying a copy: this guards shell
// execution on this machine, so a hardcoded copy is a credential in git.
const TOKEN = (process.env.IDUPI_TOKEN
    || readFileSync(new URL("../.idupi-token", import.meta.url), "utf8")).trim();

async function testRemove() {
    // 1. Add dummy project
    const addReq = http.request({
        hostname: "localhost",
        port: 8788,
        path: "/api/v1/projects/add",
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Authorization": `Bearer ${TOKEN}`
        }
    }, (res) => {
        let data = "";
        res.on("data", c => data += c);
        res.on("end", () => {
            console.log("Add response:", res.statusCode, data);
        });
    });
    addReq.end(JSON.stringify({ name: "Dummy Test Project", path: "C:\\dev" }));
}

testRemove();
