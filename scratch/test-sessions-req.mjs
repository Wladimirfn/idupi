import http from "node:http";
import fs from "node:fs";

const token = fs.readFileSync("./.idupi-token", "utf8").trim();

const req = http.request({
    hostname: "127.0.0.1",
    port: 8788,
    path: "/api/v1/sessions",
    headers: {
        "Authorization": `Bearer ${token}`
    }
}, (res) => {
    let d = "";
    res.on("data", c => d += c);
    res.on("end", () => {
        console.log("Status:", res.statusCode);
        console.log("Sessions:", d);
    });
});
req.on("error", e => console.error("Error:", e.message));
req.end();
