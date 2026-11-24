# Must Manager

App Android (APK) para monitorizar por Bluetooth el BMS de la batería LiFePO4 de los equipos solares todo-en-uno **Must HBP3000 LV** (inversor + batería en el mismo mueble). Lee los datos en tiempo real, los guarda en el dispositivo, muestra gráficas y métricas de consumo, y exporta a CSV. **Funciona 100 % sin internet.**

La usará Jhonny en su casa y la compartirá con amigos que tienen el mismo sistema, así que tiene que ser robusta, fácil de instalar y fácil de usar.

## Cómo trabajar con Jhonny
- Responder siempre en español, con un tono cercano.
- Ir paso a paso: una acción o recomendación a la vez, comprobar el resultado y luego seguir. Nada de listas largas de alternativas.
- Explicar el porqué de cada decisión técnica. Es artista 3D profesional (Unreal, 3ds Max, ZBrush, Substance), domina Git y le gusta la automatización y entender cómo funcionan las cosas.

## Reglas del proyecto
- **Solo lectura.** La app NUNCA escribe parámetros en el BMS: es imposible desconfigurar las protecciones de la batería.
- **Sin internet.** Nada de servicios en la nube ni dependencias de red en tiempo de ejecución. En Cuba la conexión es escasa y cara.
- **Pensada para varios usuarios.** El firmware del BMS puede variar entre unidades: detectar el dispositivo y mostrar un error claro si no responde como se espera, en vez de mostrar datos basura.
- El Bluetooth del BMS suele admitir una sola conexión: la app oficial (BMS tools) debe estar cerrada mientras se usa Must Manager.

## Contexto de uso (por qué importa)
- La red eléctrica llega unas 3 h cada ~20 h. La batería casi nunca pasa del 90 %.
- La prioridad del usuario es **ahorrar batería para llegar al amanecer**. Las métricas más valiosas son:
  - consumo actual (W) y consumo nocturno acumulado (kWh),
  - estimación de horas restantes hasta el corte del 15 % de SOC,
  - aporte del sol y de la red (carga) frente al consumo (descarga),
  - salud de las celdas (diferencia entre la celda más alta y la más baja).

## Datos del BMS conocidos
- Se conecta por Bluetooth (BLE) desde una tablet Android con la app oficial "BMS tools". También tiene WiFi, pero se prefiere BLE.
- Software del BMS: `P8S200A-41080-1.03` · hardware: `21887V130` (8S, 200 A). Por BLE **no** usa el protocolo PACE de texto: usa uno binario propio (ver abajo).
- Protocolos hacia el inversor: RS485 = `LEOCH_2020`; CAN = sin protocolo.
- Datos que ofrece la app oficial: SOC, SOH, voltaje y corriente del pack, ciclos, capacidad restante/total/diseño (Ah), voltaje de las 8 celdas (máx/mín y su posición), temperaturas, estado y alarmas.
- Lectura de referencia (22-nov-2026, 17:45): SOC 62 %, 26,53 V, −10,7 A (descargando ~285 W), restante 173,6 Ah de 280 Ah, celdas 3313–3316 mV, 212 ciclos.
- Lectura de referencia completa con las 8 celdas y los bits de estado (23-nov-2026, capturada a la vez que el HCI log): `docs/lectura_referencia_2026-11-23.md`.
- La pestaña "Historical data" de la app oficial está vacía (no hay historial ni exportación). Ese es el motivo de este proyecto.

## Servicios BLE del BMS (nRF Connect, 23-nov-2026)
- Nombre BLE: `PC-BDC4` · MAC `7C:3E:82:1B:BD:C4` (el sufijo del nombre = últimos bytes de la MAC; en otras unidades cambiará → detectar por prefijo `PC-` y/o por el servicio `FFF0`).
- Servicio `0000fff0-0000-1000-8000-00805f9b34fb`:
  - `FFF1` [Notify, Read] → **RX**: respuestas del BMS (confirmado).
  - `FFF2` [Read, Write, WriteNoResp] → **TX**: peticiones al BMS (confirmado).
  - `FFF3` [Notify, Read, Write], `FFFA` [R, W, WNR], `FFFB` [N, R, W, WNR] → BMS tools no las usa. No tocar.
- **Protocolo descifrado y validado: `docs/protocolo_bms.md`.** Tramas binarias `9A … 9D` con CRC-16/MODBUS, pregunta-respuesta.
- La app solo enviará los **5 comandos de lectura** de la lista blanca de `docs/protocolo_bms.md`, escritos como constantes. Es la forma de cumplir la regla de solo lectura: nunca se construyen tramas arbitrarias.
- Los logs en bruto (bug report / `btsnoop_hci.log`) contienen datos personales de la tablet: **no se suben a Git**.

## Dispositivo de pruebas
- Todas las pruebas se hacen en la tablet de Jhonny: **Samsung Galaxy Tab S9 FE** (One UI, Android 16). No usa el teléfono para este proyecto. Dar siempre las rutas de menú de Samsung.

## Repositorio, entorno y compilación
- GitHub (privado): https://github.com/YoniValdes/must_manager — rama `main`. En el PC: `D:\Git_Projects\must_manager` (Git Credential Manager con la cuenta YoniValdes; sin `gh`).
- Tablet: Termux (`~/proyectos/`, `gh` autenticado). Ritual: `git pull` al empezar, commit y push al terminar.
- Arquitectura: interfaz en `app/` (HTML + JS) y Java solo para Bluetooth/almacenamiento, igual que el proyecto `analitica` (`D:\Git_Projects\analitica`). **Sin Gradle ni Kotlin** para no depender de descargas grandes; Java 8 (`--release 8`, sin lambdas).
- SDK portable en `D:\Android` (`jdk17`, `sdk` con build-tools 35.0.0, platform android-34 y platform-tools/adb; zips en `descargas\`).
- Compilar: `.\android\build.ps1` (→ `dist\`), y con `-Install` la instala por USB con adb. Firma en `secretos\` (fuera de Git; hacer copia de seguridad).
- Pruebas: `.\tools\test-protocol.ps1` (protocolo en Java), `.\tools\test-xlsx.ps1` (escritor de .xlsx), `node tools\test-webble.js` (protocolo en JS), `node tools\test-status.js` (trama de estado) y `node tools\check-html.js` (sintaxis de los scripts de `index.html`, ids repetidos y `$("id")` inexistentes). Pasarlas antes de cada commit.
- Pruebas de diseño en el navegador (sin BMS): `index.html?demo` (3 h de historial simulado), `&hora=HH:MM` (cielo de la autonomía a esa hora), `&tema=…&neon=0&hc=1&panel=tema`. Los parámetros de prueba no se guardan como preferencia.
- Versión web: `app/index.html` usa Web Bluetooth (`app/webble.js`) si se abre en Chrome desde `localhost` o https. Fuera de Chrome muestra una demo.
- CI/CD con GitHub Actions para publicar releases: previsto en el paso 6.

## Sistema de referencia (casa de Jhonny)
- Equipo: Must HBP3000 LV (MD: HBP30-3024 LV). Inversor de 3000 W, onda senoidal pura, salida 120 V / 60 Hz.
- Batería: LiFePO4 25,6 V (8S) 280 Ah = 7168 Wh.
- Cargador solar MPPT: 24 V, 80 A, entrada FV 30–230 V (Voc máx. 245 V).
- Cargador desde la red: 40 A.
- Paneles: 2 × Trina TSM-510NEG18R.28 (510 W; Voc 40,6 V; Vmp 33,7 V; Imp 15,14 A) en serie.
- Configuración del inversor: 01 UTI · 02 amplio (vdE) · 03 110 V · 04 60 Hz · 10 SNU · 13 40 A · 14 Li · 37 SOC · 38 15 % · 39 95 %.

## Plan
1. **Descubrir el servicio y las características BLE del BMS** con nRF Connect (escanear, conectar, capturar la lista de servicios y características). ✅ Hecho
2. Capturar el tráfico entre BMS tools y el BMS (Opciones de desarrollador → "Registro de búsqueda de HCI Bluetooth" / HCI snoop log) y descifrar las tramas. ✅ Hecho (queda el fleco de los bits de estado, ver `docs/protocolo_bms.md`)
3. Prototipo mínimo: conectar y mostrar voltaje, corriente, SOC y celdas. ✅ Hecho (23-nov-2026): versión web y APK v0.3 funcionando con el BMS real.
   - Tarjeta de autonomía (hasta el 15 %, media de 5 min, hora de llegada, amanecer 07:00 y W máximos para llegar), lista para elegir el BMS (recuerda el último y solo reconecta a ese), dashboard a "--" sin conexión y en gris mientras reconecta.
   - Pendiente menor: la versión de firmware (trama `00 01`, solo llega al activar las notificaciones) no aparece en "Dispositivo"; el nº de serie sí. A Jhonny no le corre prisa.
   - Orden acordado: primero funciones (registro → consumo nocturno → gráficas → exportar CSV/XLSX para analítica) y después la parte visual.
4. Registro periódico local y gráficas. ✅ Hecho (v0.4–v0.9): servicio en primer plano, SQLite, resumen de la noche, gráficas de 24 h, alertas y trama de estado. Detalle en "Estado de la app".
5. Exportar a CSV y XLSX. ✅ Hecho (v0.10–v0.11).
   - Parte visual (v0.12–v0.20) hecha también: escena del cielo, Personalizar, KPIs con estado, medidores de la noche, panel a todo el ancho, Acerca de.
6. Compartir con los amigos. ✅ Hecho: la APK v0.20 ya la usan amigos de Jhonny con el mismo sistema. Lleva la infografía de ayuda (`docs/infografia/`) y el botón Acerca de con el correo de contacto. Opcional, sin prisa: publicar versiones con GitHub Actions.
- **Idea futura (sin internet):** calcular el amanecer y el atardecer de cada día con la fórmula astronómica (NOAA) a partir de la latitud, la longitud y la fecha. El usuario elegiría su provincia o municipio en una lista de coordenadas guardada en la app. Ahora son horas fijas en Ajustes (07:00 / 18:00), y en Cuba varían más de 1 h a lo largo del año.
7. **Fase futura: leer el inversor** (cuando la app del BMS esté completa). El BMS solo ve la corriente neta de la batería (sol + red − consumo), así que no puede separar el aporte de los paneles ni el consumo real de la casa con sol. Solo el inversor conoce los W de FV, de red y de carga. Hay que averiguar su puerto de comunicación (independiente del BMS) y su protocolo, y capturar y descifrar el tráfico como con el BMS. Mientras tanto, la tarjeta de autonomía avisa de día de que la estimación incluye el sol y que es fiable de noche.

## Notas
- El nombre "Must" es una marca de MUST Power. Para compartirla entre amigos no hay problema, pero si algún día se publica en Google Play conviene cambiarlo o dejar claro que es una app no oficial.
- El BMS solo ve el consumo total de la casa. El consumo por aparato se obtiene apagando aparatos uno a uno y viendo cuánto baja la corriente (W ≈ ΔA × 26,5 V).

## Estado de la app (v0.21, 24-nov-2026)
Versión en `android/AndroidManifest.xml` (`versionName` 0.21).

**Sistemas compatibles (v0.21):** baterías de **24 V (8S)**, como el HBP3000 LV de Jhonny, y de **48 V (16S)**, como el **HBP3300 TLV** de un amigo (6 kW, 280 Ah ≈ 14,3 kWh, ~55,7 V; mismo BMS, protocolo y signo de corriente). Con la v0.20 su app decía "respuesta del BMS con formato inesperado (valores fuera de rango)" porque solo se aceptaban de 10 a 40 V.
- Se acepta un voltaje de pack de 10 a 70 V (`BmsProtocol.MIN_PACK_V`/`MAX_PACK_V`, y lo mismo en `webble.js`). El error dice ahora qué valor falló ("voltaje fuera de rango: 75.0 V").
- La pantalla detecta la batería sola: nº de celdas de la trama `0A 02` (antes de recibirla, por el voltaje: > 36 V → 16) y capacidad de diseño en Ah. kWh = celdas × 3,2 V × Ah. Se guarda en `localStorage` (`mm-battery`) para el historial sin conexión.
- Los umbrales del voltaje van **por celda** (3,0 / 3,1 / 3,55 / 3,6 V).
- Ajustes nuevos: **Inversor** en W (vacío = automático: 24 V → 3000 W, 48 V → 6000 W; mide el medidor de pico, con verde < 50 % y ámbar < 80 %) y **Paneles** en W (1020 por defecto). De día, "llegó la corriente" salta por encima de paneles × 1,03 (antes, 1050 W fijos).
- Exportar: tantas columnas "Celda N" como celdas tenga la batería (las de la lectura más reciente del periodo).
- **Varias baterías en paralelo:** al conectar se pide una vez el resumen (`0A 00`, ya en la lista blanca; antes no se usaba) y su byte 0 da el nº de baterías (`BmsProtocol.parsePackCount`, mensaje `{type:"packs"}`). Si hay más de una, un aviso ámbar bajo la barra de estado dice que solo se muestra la batería 1. **Pendiente:** mostrar el total, para lo que hace falta una captura de BMS tools de alguien con 2 baterías (los datos de cada pack y los del resumen). Cada versión es un commit `vX.Y: …` con el detalle en el cuerpo del mensaje (`git log`).

**Java (`android/src/com/jhonny/mustmanager/`)**
- `MainActivity`: WebView con `app/` y puente `Android` hacia la página. Los enlaces `mailto:` abren la app de correo; cualquier otra navegación fuera de `file:///android_asset/` se bloquea.
- `BmsClient` + `BmsProtocol`: conexión BLE y las 5 tramas de la lista blanca. Reconexión con `connectGatt(autoConnect=true)` al BMS elegido, sin búsquedas (Android limita a 5 búsquedas cada 30 s y las bloquea con la pantalla apagada); mensaje claro si salta `SCANNING_TOO_FREQUENTLY`.
- `MonitorService`: servicio en primer plano (`connectedDevice`) con notificación fija (SOC y W) y botón Desconectar, y wake lock parcial para seguir leyendo con la tablet bloqueada. Si Android lo cierra, reconecta al último BMS. Tras **actualizar** la app no reanuda solo (guarda la versión con la que empezó).
- `ReadingStore`: SQLite (BD v2). Tabla de lecturas, una fila cada 10 s (SOC, V, A, W, Ah, 8 celdas, temperaturas y `dt` para integrar energía sin contar los huecos). No guarda nada hasta tener también las celdas. Tabla `status_log`: cada cambio de la trama de estado con su SOC y corriente.
- `AlertManager` (dentro del servicio, funciona con la pantalla apagada; canal "Alertas de la batería", prioridad alta):
  - SOC bajo: aviso al 30 % y urgente al 20 %. Salta una vez y se rearma a +5 puntos.
  - Llegó la corriente: media de 1 min > 150 W de noche o > 1050 W de día, sostenida 1 min. Se fue: vuelve a descargar (< −100 W) durante 1 min.
  - No llegas al amanecer: media de 5 min, solo de noche. Repite solo si empeora más de 30 min.
  - Sin conexión más de 5 min, con aviso de recuperación.
- `Settings`: umbrales, alertas sí/no, amanecer, atardecer y % de corte, validados en Java. La pantalla los usa también para la autonomía.
- `Exporter` + `XlsxWriter`: Excel con tres hojas (Lecturas cada 10 s con celdas y energía por intervalo; Por hora con kWh cargados/descargados, consumo medio, pico y carga media; Estado del BMS) o CSV (Lecturas). Periodo: 24 h, 7 días, 30 días o todo. El .xlsx se escribe sin librerías (zip + XML) y se validó con SheetJS. Se guarda en Descargas/MustManager con MediaStore, sin permisos (en Android 7–9, en la carpeta de la app), y se puede compartir.
- Permisos: el de notificaciones (`POST_NOTIFICATIONS`) se pide una vez, aunque ya esté concedido el de Bluetooth.

**Interfaz (`app/index.html`, `app/status.js`, `app/webble.js`; ECharts 5.6.1 local)**
- Cabecera: Conectar, campana (alertas y ajustes: rellena = alertas activas y notificaciones permitidas; solo silueta = desactivadas o bloqueadas), exportar, paleta y Acerca de. Un solo panel abierto a la vez. En el móvil la cabecera pasa a dos líneas.
- Autonomía: escena plana animada según la hora (alba, mañana, día, atardecer, crepúsculo, noche) con colinas en tres capas, arbolitos, nubes con vaivén y estrellas. El sol y la luna recorren la mitad derecha (la izquierda la ocupan el texto y su velo). Número con el color de la situación (verde llega, ámbar justo, rojo no llega, azul cargando), siempre con texto. Respeta las "animaciones reducidas" del sistema.
- KPIs con color de estado, solo en el texto, sin relleno ni neón:
  - Potencia y corriente: verde por debajo del consumo máximo para llegar al amanecer, ámbar a menos del 10 % de ese límite, rojo por encima, azul cargando.
  - Voltaje: normal entre 24,8 y 28,4 V; muy bajo < 24,0 V; muy alto > 28,8 V.
  - Restante: umbrales de las alertas (30 % / 20 %).
- Tarjeta del SOC cuadrada (252 px) con anillo.
- "Esta noche / Anoche": medidores semicirculares de energía gastada frente a la disponible al anochecer, consumo medio frente al límite para llegar, pico frente a los 3000 W del inversor y energía que entró hoy frente a los 7,2 kWh. Más la cobertura y el SOC de inicio → fin.
- Gráfica "Batería y consumo · últimas N horas": dos paneles con cursor sincronizado. Arriba el SOC en área verde. Abajo la carga (azul, ≥ 0) y el consumo (ámbar, ≤ 0) como dos series, con línea del cero. Huecos visibles. Con menos de 3 h de datos muestra 3 h.
- "Estado del BMS" (`status.js`, trama `0A 03` con estructura PACE): carga y descarga permitidas o cortadas, protecciones, balanceo, avisos y fallos. Solo están confirmados Fully (byte 2 b7) y CFET/DFET/pack (byte 3 b1–b3); el resto sale como "por confirmar".
- Personalizar: 10 paletas (Original, Prado, Cielo, Coral, Noche, Lavanda, Oro, Cyberpunk, Esmeralda, Grafito), neón y alto contraste, recordados en el dispositivo. Acento `--acc` separado de los colores de estado, que no cambian con la paleta.
- Panel a todo el ancho (tablet en horizontal o vertical y móvil); las 8 celdas caben en una fila en pantallas anchas.
- Acerca de: versión, autor y correo de contacto. El asunto del correo lleva la versión, para saber qué versión usa quien escribe.
- `docs/infografia/`: guía visual del panel (`infografia.html` → `guia_del_panel.png`) para compartir con los amigos.

**Lecciones**
- `[hidden]` siempre debe ocultar: los botones con `display` propio lo ignoraban. Hay una regla global.
- En ECharts, `visualMap` fallaba con dos paneles (`reading 'coord'`) y la potencia no se dibujaba. Se resolvió con dos series separadas.
- `build.ps1` empaqueta los assets con rutas "/" (aapt2 en Windows ponía ""), y con `-Install` detiene la app antes de instalar para liberar el BMS.

## Estado
- Nombre del proyecto y de la APK decidido: **Must Manager**.
- Paso 1 hecho (23-nov-2026): servicios BLE descubiertos (ver sección "Servicios BLE del BMS").
- Paso 2 hecho (23-nov-2026): protocolo descifrado desde el HCI log; todos los valores coinciden con la lectura de referencia.
- v0.21 (24-nov-2026): compatible también con baterías de 48 V (16S). Pendiente: que el amigo del HBP3300 TLV la pruebe y ponga los W de sus paneles en Ajustes.
- Pasos 3, 4 y 5 hechos (23-nov-2026): APK v0.20 con registro, gráficas, alertas, exportación y la parte visual. Paso 6 hecho: la v0.20 ya está compartida con amigos que tienen el mismo sistema.
