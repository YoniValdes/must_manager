// Comprueba app/index.html: sintaxis de los <script> en línea e ids únicos.
// Ejecutar: node tools/check-html.js
"use strict";
const fs = require("fs");
const path = require("path");
const html = fs.readFileSync(path.join(__dirname, "..", "app", "index.html"), "utf8");
let errors = 0;

[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].forEach((m, i) => {
  try { new Function(m[1]); } catch (e) { console.log(`script ${i}: ${e.message}`); errors++; }
});

const ids = {};
for (const m of html.matchAll(/\sid="([^"]+)"/g)) ids[m[1]] = (ids[m[1]] || 0) + 1;
Object.entries(ids).filter(([, n]) => n > 1).forEach(([id, n]) => { console.log(`id repetido: ${id} (${n})`); errors++; });

// Todo $("x") usado en el JS debe existir en el HTML.
for (const m of html.matchAll(/\$\("([^"]+)"\)/g)) {
  if (!ids[m[1]]) { console.log(`$("${m[1]}") no existe en el HTML`); errors++; }
}

console.log(errors ? `${errors} problemas` : "HTML y JS correctos");
process.exit(errors ? 1 : 0);
