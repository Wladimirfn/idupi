import http from "node:http";

const TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

const endpoints = [
    { method: "GET", path: "/api/v1/status" },
    { method: "GET", path: "/api/v1/projects" },
    { method: "GET", path: "/api/v1/sessions" },
    { method: "GET", path: "/api/v1/models/available" },
    { method: "GET", path: "/api/v1/fs/browse" },
    { method: "GET", path: "/api/v1/fs/browse?path=C%3A%5C" },
    { method: "GET", path: "/api/v1/fs/browse?path=C%3A%5CUsers" },
    { method: "GET", path: "/api/v1/terminals" },
    { method: "GET", path: "/api/v1/alerts" }
];

async function testEndpoint(ep) {
    return new Promise((resolve) => {
        const req = http.request({
            hostname: "localhost",
            port: 8788,
            path: ep.path,
            method: ep.method,
            headers: {
                "Authorization": `Bearer ${TOKEN}`
            }
        }, (res) => {
            let body = "";
            res.on("data", chunk => body += chunk);
            res.on("end", () => {
                console.log(`[${res.statusCode}] ${ep.method} ${ep.path} -> ${body.slice(0, 100)}`);
                resolve();
            });
        });
        req.on("error", (e) => {
            console.error(`[ERROR] ${ep.method} ${ep.path}:`, e.message);
            resolve();
        });
        req.end();
    });
}

async function run() {
    for (const ep of endpoints) {
        await testEndpoint(ep);
    }
}
run();
