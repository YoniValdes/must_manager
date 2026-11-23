// Prueba app/status.js con las tramas de estado reales capturadas.
// Ejecutar: node tools/test-status.js
"use strict";
const fs = require("fs");
const path = require("path");
global.window = {};
eval(fs.readFileSync(path.join(__dirname, "..", "app", "status.js"), "utf8"));
const D = window.BmsStatus.decode;
let fails = 0;
const check = (ok, what) => { console.log((ok ? "  OK    " : "  FALLO ") + what); if (!ok) fails++; };

console.log("Batería llena (23-nov 14:14, BMS tools: Fully, DFET On, CFET Off)");
let s = D("02 00 80 04 06 00 00 00 00 00 00 00 00");
check(s.full === true, "protección Fully");
check(s.chargeAllowed === false, "carga cortada (CFET off)");
check(s.dischargeAllowed === true, "descarga permitida (DFET on)");
check(s.protections.length === 1 && s.protections[0].confirmed, "una sola protección y confirmada: " + JSON.stringify(s.protections));
check(s.balancing.length === 0 && s.faults.length === 0 && s.alarms.length === 0 && s.unknown.length === 0, "sin balanceo, fallos, avisos ni bits desconocidos");

console.log("Descargando (23-nov 15:55, −20,7 A)");
s = D("02 00 00 0E 06 00 00 00 00 00 00 00 00");
check(!s.full && s.chargeAllowed && s.dischargeAllowed && s.packPower, "sin Fully · CFET on · DFET on · pack on");
check(s.protections.length === 0 && s.unknown.length === 0, "sin protecciones ni bits desconocidos");

console.log("Casos inventados (estructura)");
s = D("02 00 00 0E 06 00 00 24 00 00 00 00 00");
check(JSON.stringify(s.balancing) === "[3,6]", "balanceo de celdas 3 y 6: " + JSON.stringify(s.balancing));
s = D("02 00 00 0E 06 00 00 00 00 00 00 01 00");
check(s.unknown.length === 1 && s.unknown[0].byte === 11, "bit desconocido en el byte 11 detectado");
check(D("02 00") === null, "trama corta → null");

console.log(fails ? `\n${fails} FALLOS` : "\nTODO CORRECTO");
process.exit(fails ? 1 : 0);
