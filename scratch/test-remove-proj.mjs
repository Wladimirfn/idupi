import http from "node:http";

const TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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
