/*
 * Motor Web Bluetooth: permite usar index.html directamente en Chrome (Android o PC con BLE),
 * sin la APK. Es la misma lógica que BmsProtocol.java + BmsClient.java, en JavaScript.
 * Envía a la página los mismos mensajes que el puente Java: window.bms({type: ...}).
 *
 * Requisitos de Chrome: página servida por https o desde http://localhost, y gesto del usuario
 * (pulsar un botón) para elegir el dispositivo.
 */
(function () {
  "use strict";

  var SERVICE = 0xfff0, NOTIFY = 0xfff1, WRITE = 0xfff2;
  var RESPONSE_TIMEOUT_MS = 2500, POLL_PERIOD_MS = 3000, NO_DATA_TIMEOUT_MS = 15000, RECONNECT_DELAY_MS = 5000;

  function hex(s) { return new Uint8Array(s.trim().split(/\s+/).map(function (x) { return parseInt(x, 16); })); }

  // ---------------------------------------------------------------- LISTA BLANCA (solo lectura)
  var CMD_DEVICE_INFO = hex("9A 00 00 00 04 00 00 00 28 C8 9D");
  var CMD_SUMMARY = hex("9A 00 00 0A 00 00 00 00 19 51 9D");
  var CMD_PACK_BASIC = hex("9A 00 00 0A 01 00 00 02 01 01 1B 9C 9D");
  var CMD_PACK_CELLS = hex("9A 00 00 0A 02 00 00 02 01 01 28 9C 9D");
  var CMD_PACK_STATUS = hex("9A 00 00 0A 03 00 00 02 01 01 F9 9D 9D");
  var WHITELIST = [CMD_DEVICE_INFO, CMD_SUMMARY, CMD_PACK_BASIC, CMD_PACK_CELLS, CMD_PACK_STATUS];
  var CYCLE = [CMD_PACK_BASIC, CMD_PACK_CELLS, CMD_PACK_STATUS];

  function same(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }
  function isWhitelisted(cmd) { return WHITELIST.some(function (w) { return same(w, cmd); }); }
  function keyOf(f) { return (f[3] << 8) | f[4]; }

  function crc16(d, off, len) {
    var crc = 0xffff;
    for (var i = off; i < off + len; i++) {
      crc ^= d[i];
      for (var b = 0; b < 8; b++) crc = (crc & 1) ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
    }
    return crc;
  }

  // ---------------------------------------------------------------- reensamblado
  var buf = [];
  var rejected = 0;
  function feed(chunk) {
    var out = [];
    for (var i = 0; i < chunk.length; i++) buf.push(chunk[i]);
    if (buf.length > 4096) buf = [];
    for (;;) {
      var start = buf.indexOf(0x9a);
      if (start < 0) { buf = []; break; }
      if (start > 0) buf.splice(0, start);
      if (buf.length < 8) break;
      var dataLen = (buf[6] << 8) | buf[7];
      if (dataLen > 512) { buf.shift(); rejected++; continue; }
      var total = 8 + dataLen + 3;
      if (buf.length < total) break;
      var crc = (buf[8 + dataLen] << 8) | buf[9 + dataLen];
      if (buf[total - 1] === 0x9d && crc16(buf, 0, 8 + dataLen) === crc) {
        out.push({ key: keyOf(buf), data: new Uint8Array(buf.slice(8, 8 + dataLen)) });
        buf.splice(0, total);
      } else {
        buf.shift();
        rejected++;
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- decodificación
  function u16(d, o) { return (d[o] << 8) | d[o + 1]; }
  function s16(d, o) { var v = u16(d, o); return v & 0x8000 ? v - 0x10000 : v; }
  function kelvin(raw) { return (raw - 2730) / 10; }

  function parseBasic(d) {
    if (d.length < 43) throw new Error("trama de datos básicos demasiado corta");
    var b = {
      type: "basic",
      currentA: s16(d, 1) / 100, voltageV: u16(d, 3) / 100,
      remainingAh: u16(d, 5) / 100, fullAh: u16(d, 7) / 100, designAh: u16(d, 9) / 100,
      soc: d[11], soh: d[12], cycles: u16(d, 13),
      independentV: u16(d, 23) / 100, independentA: s16(d, 25) / 100,
      tempMaxSensor: d[27], tempMaxC: kelvin(u16(d, 28)),
      tempMinSensor: d[30], tempMinC: kelvin(u16(d, 31)),
      tempMosC: kelvin(u16(d, 33)), tempAmbientC: kelvin(u16(d, 35)),
      cellMaxIndex: d[37], cellMaxMv: u16(d, 38), cellMinIndex: d[40], cellMinMv: u16(d, 41)
    };
    // Rango amplio: baterías de 24 V (8S, HBP3000 LV) y de 48 V (16S, HBP3300 TLV y similares).
    if (b.soc > 100) throw new Error("SOC fuera de rango: " + b.soc + " %");
    if (b.voltageV < 10 || b.voltageV > 70) throw new Error("voltaje fuera de rango: " + b.voltageV + " V");
    return b;
  }

  function parseCells(d) {
    var n = d[3];
    if (d.length < 4 || n < 1 || n > 32 || d.length < 4 + 4 * n) throw new Error("trama de celdas rara");
    var mv = [], temps = [];
    for (var i = 0; i < n; i++) {
      mv.push(u16(d, 4 + 4 * i));
      var t = u16(d, 6 + 4 * i);
      temps.push(t === 0 ? null : kelvin(t));
    }
    return { type: "cells", packVoltageV: u16(d, 1) / 100, mv: mv, tempC: temps };
  }

  function parseText(d) {
    var parts = [], run = "";
    for (var i = 0; i <= d.length; i++) {
      var c = i < d.length ? d[i] : 0;
      if (c >= 0x20 && c < 0x7f) run += String.fromCharCode(c);
      else { if (run.trim().length >= 4) parts.push(run.trim()); run = ""; }
    }
    return parts.join(" · ");
  }

  function toHex(d) {
    return Array.prototype.map.call(d, function (x) { return (x < 16 ? "0" : "") + x.toString(16).toUpperCase(); }).join(" ");
  }

  // ---------------------------------------------------------------- conexión
  var emit = function (m) { if (window.bms) window.bms(m); };
  var device = null, tx = null, rx = null;
  var running = false, sessionId = 0, lastGood = 0, everGotData = false;
  var waiter = null;

  function state(s, msg) { emit({ type: "state", state: s, message: msg }); }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function handleFrame(f) {
    try {
      if (f.key === 0x0a01) emit(parseBasic(f.data));
      else if (f.key === 0x0a02) emit(parseCells(f.data));
      else if (f.key === 0x0a03) emit({ type: "status", hex: toHex(f.data) });
      else if (f.key === 0x0a00) { var n = f.data.length ? f.data[0] : 0; if (n >= 1 && n <= 32) emit({ type: "packs", packs: n }); }
      else if ((f.key >> 8) === 0x00) { var t = parseText(f.data); if (t) emit({ type: "device", key: f.key, text: t }); }
      lastGood = Date.now();
      if (!everGotData && (f.key >> 8) === 0x0a) { everGotData = true; state("connected", "Conectado a " + (device.name || "el BMS") + " (Web Bluetooth)"); }
    } catch (e) {
      state("connected", "Aviso: respuesta del BMS con formato inesperado (" + e.message + ").");
    }
    if (waiter && waiter.key === f.key) { var w = waiter; waiter = null; w.resolve(true); }
  }

  function onNotify(ev) {
    var v = ev.target.value;
    feed(new Uint8Array(v.buffer, v.byteOffset, v.byteLength)).forEach(handleFrame);
  }

  function waitFor(key, ms) {
    return new Promise(function (resolve) {
      waiter = { key: key, resolve: resolve };
      setTimeout(function () { if (waiter && waiter.resolve === resolve) { waiter = null; resolve(false); } }, ms);
    });
  }

  function send(cmd) {
    // Garantía de solo lectura: nada fuera de la lista blanca sale hacia el BMS.
    if (!isWhitelisted(cmd)) throw new Error("Comando no permitido");
    return tx.writeValueWithoutResponse ? tx.writeValueWithoutResponse(cmd) : tx.writeValue(cmd);
  }

  async function openSession() {
    var id = ++sessionId;
    state("connecting", "Conectando con " + (device.name || "el BMS") + "…");
    var server = await device.gatt.connect();
    var svc;
    try {
      svc = await server.getPrimaryService(SERVICE);
      rx = await svc.getCharacteristic(NOTIFY);
      tx = await svc.getCharacteristic(WRITE);
    } catch (e) {
      running = false;
      device.gatt.disconnect();
      state("error", "Este dispositivo no parece un BMS compatible (no tiene el servicio Bluetooth esperado). No se mostrarán datos.");
      return;
    }
    buf = [];
    rx.addEventListener("characteristicvaluechanged", onNotify);
    await rx.startNotifications();
    // Para el nombre propio: Chrome no da la MAC, pero device.id es fijo para este BMS en esta web.
    emit({ type: "bms", address: device.id, name: device.name || "" });
    state("connected", "Conectado a " + (device.name || "el BMS") + ". Leyendo datos…");
    lastGood = Date.now();
    await sleep(800); // al activar las notificaciones el BMS manda solo su información (versión)
    // El número de serie se pide una vez (igual que hace BMS tools al conectar).
    await send(CMD_DEVICE_INFO);
    await waitFor(keyOf(CMD_DEVICE_INFO), RESPONSE_TIMEOUT_MS);
    await sleep(150);
    // Resumen del sistema, una vez: dice cuántas baterías hay en paralelo (la app solo muestra la 1).
    await send(CMD_SUMMARY);
    await waitFor(keyOf(CMD_SUMMARY), RESPONSE_TIMEOUT_MS);
    await sleep(150);

    while (running && id === sessionId && device.gatt.connected) {
      for (var i = 0; i < CYCLE.length && running && id === sessionId; i++) {
        await send(CYCLE[i]);
        await waitFor(keyOf(CYCLE[i]), RESPONSE_TIMEOUT_MS);
        await sleep(150);
      }
      if (Date.now() - lastGood > NO_DATA_TIMEOUT_MS) {
        state("reconnecting", (everGotData ? "El BMS ha dejado de responder." :
          "El BMS no responde como se esperaba. Puede que este modelo o firmware no sea compatible.") + " Reintentando…");
        device.gatt.disconnect();
        return;
      }
      await sleep(POLL_PERIOD_MS);
    }
  }

  function onDisconnected() {
    if (!running) return;
    state("reconnecting", "Se perdió la conexión con el BMS. Reintentando en 5 s…");
    setTimeout(function () {
      if (running && device && !device.gatt.connected) openSession().catch(onError);
    }, RECONNECT_DELAY_MS);
  }

  function onError(e) {
    if (!running) return;
    var msg = String(e && e.message || e);
    if (/NetworkError|connect|GATT/i.test(msg)) {
      onDisconnected();
    } else {
      running = false;
      state("error", "Error: " + msg);
    }
  }

  async function connect() {
    if (!navigator.bluetooth) {
      state("error", "Este navegador no tiene Web Bluetooth. Usa Google Chrome (en Samsung Internet no funciona).");
      return;
    }
    try {
      state("scanning", "Elige el BMS (PC-…) en la lista de Chrome. Recuerda cerrar BMS tools.");
      device = await navigator.bluetooth.requestDevice({
        filters: [{ namePrefix: "PC-" }, { services: [SERVICE] }],
        optionalServices: [SERVICE]
      });
    } catch (e) {
      state("disconnected", "No se eligió ningún dispositivo.");
      return;
    }
    device.addEventListener("gattserverdisconnected", onDisconnected);
    running = true;
    everGotData = false;
    openSession().catch(onError);
  }

  function disconnect() {
    running = false;
    sessionId++;
    if (device && device.gatt.connected) device.gatt.disconnect();
    state("disconnected", "Desconectado. El BMS queda libre para BMS tools.");
  }

  window.WebBle = {
    connect: connect, disconnect: disconnect, available: !!navigator.bluetooth,
    // Para tools/test-webble.js
    _test: { feed: feed, parseBasic: parseBasic, parseCells: parseCells, parseText: parseText,
      isWhitelisted: isWhitelisted, whitelist: WHITELIST }
  };
})();
