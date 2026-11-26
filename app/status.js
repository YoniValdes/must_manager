/*
 * Decodificación de la trama de estado del BMS (respuesta 0A 03, 13 bytes).
 * La estructura coincide con la trama de alarmas/estado del protocolo PACE.
 * CONFIRMADO con capturas reales (ver docs/protocolo_bms.md):
 *   byte 2 bit 7 = protección "Fully" (batería llena)
 *   byte 3 bit 1 = CFET (carga permitida), bit 2 = DFET (descarga permitida)
 *   byte 3 bit 3 = está saliendo corriente (descargando) y bit 5 = está entrando corriente (cargando), venga del
 *   sol o de la red: comprobado con status_log el 26-nov-2026 (en un apagón con sol y nubes cambia con cada
 *   paso de cargar a descargar). Antes se mostraba el bit 5 como "Entrada de red (ACin)", y NO lo es.
 * El resto sigue la tabla PACE y se marca como "por confirmar" hasta verlo en BMS tools.
 * Cualquier bit activo que no esté en las tablas se devuelve en `unknown` para no perder nada.
 */
(function () {
  "use strict";

  // [texto, confirmado]
  var PROTECT1 = [["Sobretensión de celda"], ["Subtensión de celda"], ["Sobretensión del pack"], ["Subtensión del pack"],
    ["Sobrecorriente de carga"], ["Sobrecorriente de descarga"], ["Cortocircuito"], null];
  var PROTECT2 = [["Temperatura alta cargando"], ["Temperatura alta descargando"], ["Temperatura baja cargando"],
    ["Temperatura baja descargando"], ["Temperatura alta del MOS"], ["Temperatura ambiente alta"],
    ["Temperatura ambiente baja"], ["Batería llena (carga cortada)", true]];
  var FAULTS = [["Fallo del MOSFET de carga"], ["Fallo del MOSFET de descarga"], ["Fallo de un sensor de temperatura"],
    ["Fallo de celda"], ["Fallo de medición"], null, null, null];
  // Indicación: bits conocidos; los que no son "estado normal" se listan aparte.
  // El bit 5 (cargando) no es un aviso: va en `charging` y no se lista.
  var INDICATION = { 0: "Limitando la corriente de carga", 4: "Polaridad invertida", 7: "Calefactor encendido" };

  function bytes(hex) {
    return String(hex || "").trim().split(/\s+/).filter(Boolean).map(function (x) { return parseInt(x, 16); });
  }

  function bitsOf(table, value, byteIndex, prefix, out, unknown) {
    for (var bit = 0; bit < 8; bit++) {
      if (!(value & (1 << bit))) continue;
      var e = table[bit];
      if (e) out.push({ text: (prefix || "") + e[0], confirmed: !!e[1] });
      else unknown.push({ byte: byteIndex, bit: bit });
    }
  }

  function decode(hex) {
    var b = bytes(hex);
    if (b.length < 10) return null;
    var r = {
      raw: hex,
      chargeAllowed: !!(b[3] & 0x02),
      dischargeAllowed: !!(b[3] & 0x04),
      packPower: !!(b[3] & 0x08), // saliendo corriente (descargando)
      charging: !!(b[3] & 0x20),  // entrando corriente (cargando: sol o red)
      full: !!(b[2] & 0x80),
      protections: [], indications: [], faults: [], alarms: [], balancing: [], unknown: [],
      control: b[4]
    };
    bitsOf(PROTECT1, b[1], 1, "", r.protections, r.unknown);
    bitsOf(PROTECT2, b[2], 2, "", r.protections, r.unknown);
    for (var bit = 0; bit < 8; bit++) {
      if ((b[3] & (1 << bit)) && INDICATION[bit]) r.indications.push({ text: INDICATION[bit], confirmed: false });
      else if ((b[3] & (1 << bit)) && bit === 6) r.unknown.push({ byte: 3, bit: 6 });
    }
    bitsOf(FAULTS, b[5], 5, "", r.faults, r.unknown);
    // Balanceo (PACE): un bit por celda; byte 7 = celdas 1–8, byte 6 = celdas 9–16. Por confirmar.
    for (var c = 0; c < 16; c++) {
      var v = c < 8 ? b[7] : b[6];
      if (v & (1 << (c % 8))) r.balancing.push(c + 1);
    }
    // Avisos (PACE): mismas causas que las protecciones, pero solo como advertencia previa.
    bitsOf(PROTECT1, b[8], 8, "Aviso: ", r.alarms, r.unknown);
    bitsOf(PROTECT2.slice(0, 7).concat([null]), b[9], 9, "Aviso: ", r.alarms, r.unknown);
    for (var i = 10; i < b.length; i++) {
      for (var k = 0; k < 8; k++) if (b[i] & (1 << k)) r.unknown.push({ byte: i, bit: k });
    }
    return r;
  }

  window.BmsStatus = { decode: decode };
})();
