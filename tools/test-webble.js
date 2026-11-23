// Prueba el protocolo de app/webble.js con tramas reales del HCI log (23-nov-2026).
// Ejecutar: node tools/test-webble.js
"use strict";
const fs = require("fs");
const path = require("path");
global.window = {};
if (typeof navigator === "undefined") global.navigator = {}; // Node 21+ ya trae navigator (sin bluetooth)
eval(fs.readFileSync(path.join(__dirname, "..", "app", "webble.js"), "utf8"));
const T = window.WebBle._test;

let fails = 0;
const check = (ok, what) => { console.log((ok ? "  OK    " : "  FALLO ") + what); if (!ok) fails++; };
const near = (a, b) => Math.abs(a - b) < 0.005;
const hex = (s) => new Uint8Array(s.trim().split(/\s+/).map((x) => parseInt(x, 16)));
const chunked = (frame) => { let out = []; for (let i = 0; i < frame.length; i += 20) out = out.concat(T.feed(frame.slice(i, i + 20))); return out; };

console.log("Lista blanca");
T.whitelist.forEach((c) => check(T.feed(c).length === 1 && T.isWhitelisted(c), "CRC válido y en lista blanca"));
check(!T.isWhitelisted(hex("9A 00 00 0B 01 00 00 00 00 00 9D")), "trama inventada rechazada");

console.log("Datos básicos (0A 01)");
const basic = hex("9a 00 00 0a 01 00 00 2b 02 00 00 0a e5 6d 5a 6d 60 6d 60 64 64 00 d4 00 00 00 00 00 00 00 00 0a f2 00 00 01 0b d7 02 0b d4 0b d8 0b f3 03 0d a2 01 0d 91 40 62 9d");
let f = chunked(basic);
check(f.length === 1 && f[0].key === 0x0a01, "reensamblada desde trozos de 20 bytes");
const b = T.parseBasic(f[0].data);
check(near(b.voltageV, 27.89) && near(b.currentA, 0) && near(b.remainingAh, 279.94), "27,89 V · 0 A · 279,94 Ah");
check(b.soc === 100 && b.soh === 100 && b.cycles === 212, "SOC 100 · SOH 100 · 212 ciclos");
check(near(b.tempMosC, 30.2) && near(b.tempAmbientC, 32.9) && near(b.tempMaxC, 30.1), "MOS 30,2 · amb. 32,9 · máx. 30,1 °C");
check(b.cellMaxMv === 3490 && b.cellMinMv === 3473, "celdas máx./mín. 3490/3473");

console.log("Celdas (0A 02)");
const cells = hex("9a 00 00 0a 02 00 00 24 02 0a e5 08 0d 91 0b d7 0d 9e 0b d4 0d a2 00 00 0d 98 00 00 0d 92 00 00 0d a1 00 00 0d a1 00 00 0d a0 00 00 05 f7 9d");
f = chunked(cells);
const c = T.parseCells(f[0].data);
check(JSON.stringify(c.mv) === "[3473,3486,3490,3480,3474,3489,3489,3488]", "8 celdas " + JSON.stringify(c.mv));
check(near(c.tempC[0], 30.1) && near(c.tempC[1], 29.8) && c.tempC[2] === null, "T1 30,1 · T2 29,8");

console.log("Robustez");
const bad = basic.slice(); bad[12] ^= 1;
check(T.feed(bad).length === 0, "bit corrupto → descartada");

console.log(fails ? `\n${fails} FALLOS` : "\nTODO CORRECTO");
process.exit(fails ? 1 : 0);
