# Protocolo BLE del BMS (descifrado 23-nov-2026)

Fuente: HCI snoop log de la Galaxy Tab S9 FE con BMS tools conectada a `PC-BDC4`,
validado contra `lectura_referencia_2026-11-23.md` (todos los valores coinciden).

## Transporte
- Servicio `FFF0`. Peticiones → escribir (Write Without Response) en **`FFF2`**. Respuestas ← notificaciones de **`FFF1`**.
- Antes hay que activar las notificaciones de `FFF1` (escribir `01 00` en su CCCD `0x2902`).
- Al activarlas, el BMS envía **solo** las tramas de información del dispositivo (`00 01`, `00 04`, `00 05`).
- Cada respuesta llega entera en una sola notificación (MTU negociado ≥ 78 bytes). Aun así, la app debe reensamblar por si el MTU es menor (buscar `9A` … `9D` y comprobar la longitud).
- Es **pregunta-respuesta**: el BMS no envía datos en vivo si no se le pregunta. BMS tools consulta cada ~3–4 s.

## Formato de trama (binario, enteros big-endian)
```
9A | 00 00 | GRP | CMD | 00 00 | LEN | DATOS (LEN bytes) | CRC_H CRC_L | 9D
```
- `GRP CMD`: `00 xx` = información del dispositivo · `0A xx` = datos en vivo.
- `CRC` = **CRC-16/MODBUS** (poly 0xA001 reflejado, init 0xFFFF) calculado desde el `9A` inicial hasta el último byte de DATOS; se transmite **big-endian**. Verificado en 74/74 tramas.

## Comandos de lectura (lista blanca, los únicos que enviará la app)
| Trama completa | Qué pide |
|---|---|
| `9A 00 00 00 04 00 00 00 28 C8 9D` | Info dispositivo (nº de serie) |
| `9A 00 00 0A 00 00 00 00 19 51 9D` | Resumen del sistema (todos los packs) |
| `9A 00 00 0A 01 00 00 02 01 01 1B 9C 9D` | Pack 1 – datos básicos |
| `9A 00 00 0A 02 00 00 02 01 01 28 9C 9D` | Pack 1 – celdas y temperaturas |
| `9A 00 00 0A 03 00 00 02 01 01 F9 9D 9D` | Pack 1 – estado / alarmas |

En `0A 01/02/03` los datos `01 01` parecen ser "pack inicial = 1, número de packs = 1".

## Respuestas
Unidades: voltaje del pack en 0,01 V · celdas en mV · capacidad en 0,01 Ah · corriente en 0,01 A (con signo; negativo = descarga, por confirmar con batería descargando) · temperatura en 0,1 K → °C = (raw − 2730) / 10.

### `0A 00` – Resumen (LEN 0x33)
| Offset | Bytes | Campo | Ejemplo |
|---|---|---|---|
| 0 | 1 | nº de packs | 01 |
| 1 | 4 | corriente total (0,01 A, con signo) | 0 |
| 5 | 4 | voltaje (0,01 V) | 0A E7 → 27,91 V |
| 9 | 4 | capacidad restante (0,01 Ah) | 280,00 |
| 13 | 4 | capacidad total | 280,00 |
| 17 | 4 | capacidad de diseño | 280,00 |
| 21 | 1 | SOC % | 100 |
| 22 | 1 | SOH % | 100 |
| 23 | 4 | ciclos | 212 |
| 27 | 4 | carga acumulada | 0 |
| 31 | 4 | descarga acumulada | 0 |
| 35 | 4 | celda máx.: pack, posición, mV (2) | 01 03 0D A3 → 3491 mV |
| 39 | 4 | celda mín.: pack, posición, mV | 01 01 0D 93 → 3475 mV |
| 43 | 4 | temp. máx.: pack, sensor, 0,1 K | 01 01 0B D7 → 30,1 °C |
| 47 | 4 | temp. mín.: pack, sensor, 0,1 K | 01 02 0B D4 → 29,8 °C |

### `0A 01` – Pack: datos básicos (LEN 0x2B)
| Offset | Bytes | Campo | Ejemplo |
|---|---|---|---|
| 0 | 1 | ¿id/estado? | 02 |
| 1 | 2 | corriente (0,01 A, con signo) | 0 |
| 3 | 2 | voltaje (0,01 V) | 27,89 V |
| 5 | 2 | restante (0,01 Ah) | 279,94 |
| 7 | 2 | total | 280,00 |
| 9 | 2 | diseño | 280,00 |
| 11 | 1 | SOC % | 100 |
| 12 | 1 | SOH % | 100 |
| 13 | 2 | ciclos | 212 |
| 15 | 4 | carga acumulada | 0 |
| 19 | 4 | descarga acumulada | 0 |
| 23 | 2 | "independent voltage" (0,01 V) | 28,02 |
| 25 | 2 | "independent current" | 0 |
| 27 | 1+2 | temp. máx.: sensor, 0,1 K | 1 → 30,1 °C |
| 30 | 1+2 | temp. mín.: sensor, 0,1 K | 2 → 29,8 °C |
| 33 | 2 | temp. MOS | 30,2 °C |
| 35 | 2 | temp. ambiente | 32,9 °C |
| 37 | 1+2 | celda máx.: posición, mV | 3 → 3490 |
| 40 | 1+2 | celda mín.: posición, mV | 1 → 3473 |

### `0A 02` – Pack: celdas (LEN 0x24)
| Offset | Bytes | Campo |
|---|---|---|
| 0 | 1 | ¿id/estado? (02) |
| 1 | 2 | voltaje del pack (0,01 V) |
| 3 | 1 | nº de celdas (8) |
| 4 | 4 × N | por celda: mV (2) + temperatura asociada en 0,1 K (2; `00 00` = sin sensor) |

Ejemplo: celdas 3473, 3486, 3490, 3480, 3474, 3489, 3489, 3488 mV; T1 (celda 1) 30,1 °C, T2 (celda 2) 29,8 °C.

### `0A 03` – Pack: estado (LEN 0x0D)
`02 00 80 04 06 00 00 00 00 00 00 00 00` con la batería llena (protección "Fully", DFET on, CFET indication off, control: limitación de carga, DFET, CFET y LED on).
**Pendiente:** asignar los bits. Hace falta capturar este comando en otros estados (descargando, cargando, con alguna alarma).

### `00 01` / `00 04` / `00 05` – Información del dispositivo
Cadenas ASCII: software `P8S200A-41080-1.03`, `P8S200A-41080-1.03-002`, hardware `21887V130`, y dos números de serie. No hace falta decodificarlas al detalle: sirven para **identificar el BMS** al conectar.

## Pendiente de confirmar
- Signo y escala de la corriente con la batería descargando y cargando (en la captura era 0 A).
- Unidades de carga/descarga acumuladas (venían a 0).
- Bits de la trama de estado `0A 03`.
