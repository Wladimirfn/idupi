import http from "node:http";
import fs from "node:fs";

const token = fs.readFileSync("./.idupi-token", "utf8").trim();

function querySessions(engine) {
    const p = engine ? `/api/v1/sessions?engine=${engine}` : "/api/v1/sessions";
    const req = http.request({
        hostname: "127.0.0.1",
        port: 8788,
        path: p,
        headers: { "Authorization": `Bearer ${token}` }
    }, (res) => {
        let d = "";
        res.on("data", c => d += c);
        res.on("end", () => {
            console.log(`[Query ${p}] Status: ${res.statusCode}`);
            try {
                const list = JSON.parse(d);
                console.log(`Total sessions returned: ${list.length}`);
                list.slice(0, 5).forEach(s => console.log(` - [${s.engine}] ${s.id} | ${s.date} | ${s.title}`));
            } catch(e) {
                console.log("Raw:", d.slice(0, 100));
            }
        });
    });
    req.on("error", e => console.log("Server not running or err:", e.message));
    req.end();
}

querySessions();
