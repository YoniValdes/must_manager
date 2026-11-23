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
- Pruebas del protocolo: `.\tools\test-protocol.ps1` (Java) y `node tools\test-webble.js` (JS).
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
3. Prototipo mínimo: conectar y mostrar voltaje, corriente, SOC y celdas. ✅ Versión web probada con el BMS real (23-nov-2026); falta probar la APK. ← EN CURSO: pulido
4. Registro periódico local (base de datos en el dispositivo) y gráficas: consumo por horas, descarga nocturna, carga solar y de red, horas restantes estimadas.
5. Exportar a CSV y XLSX para analizar los datos y hacer gráficas fuera de la app (Excel, Google Sheets, etc.).
6. Pulido para compartir: icono, nombre, instrucciones de instalación y manejo de errores.
7. **Fase futura: leer el inversor** (cuando la app del BMS esté completa). El BMS solo ve la corriente neta de la batería (sol + red − consumo), así que no puede separar el aporte de los paneles ni el consumo real de la casa con sol. Solo el inversor conoce los W de FV, de red y de carga. Hay que averiguar su puerto de comunicación (independiente del BMS) y su protocolo, y capturar y descifrar el tráfico como con el BMS. Mientras tanto, la tarjeta de autonomía avisa de día de que la estimación incluye el sol y que es fiable de noche.

## Notas
- El nombre "Must" es una marca de MUST Power. Para compartirla entre amigos no hay problema, pero si algún día se publica en Google Play conviene cambiarlo o dejar claro que es una app no oficial.
- El BMS solo ve el consumo total de la casa. El consumo por aparato se obtiene apagando aparatos uno a uno y viendo cuánto baja la corriente (W ≈ ΔA × 26,5 V).

## Estado
- Nombre del proyecto y de la APK decidido: **Must Manager**.
- Paso 1 hecho (23-nov-2026): servicios BLE descubiertos (ver sección "Servicios BLE del BMS").
- Paso 2 hecho (23-nov-2026): protocolo descifrado desde el HCI log; todos los valores coinciden con la lectura de referencia.
