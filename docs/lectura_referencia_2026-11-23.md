# Lectura de referencia — 23-nov-2026, 14:04–14:14

Capturada con BMS tools mientras se grababa el HCI snoop log (Galaxy Tab S9 FE).
Sirve para validar la decodificación de las tramas: estos valores deben salir tal cual.

## Situación
Batería recién cargada desde la red: 100 %, sin corriente, BMS en protección "Fully" (carga cortada).

## Summary data (14:04)
- SOC 100 % · SOH 100 % · 212 ciclos · 1 pack en paralelo
- Corriente 0,00 A · voltaje 27,900 V
- Capacidad restante 280,00 Ah · total 280,00 Ah · diseño 280,00 Ah
- Carga acumulada 0,00 Ah · descarga acumulada 0,00 Ah
- Celda máx. 3491 mV (celda 3) · celda mín. 3474 mV (celda 1)
- Temp. máx. 30,1 °C (sensor 1) · temp. mín. 29,8 °C (sensor 2)

## PACK data → Basic data (14:10)
- Voltaje pack 27,900 V · "Independent voltage" 28,020 V · corriente 0,00 A
- Restante 279,94 Ah · total 280,00 Ah · diseño 280,00 Ah · SOH 100 %
- Celda máx. 3491 mV (3) · mín. 3474 mV (1)
- Temp. máx. 30,1 °C (1) · mín. 29,8 °C (2) · MOS 30,2 °C · ambiente 32,9 °C

## PACK data → Voltage temperature (14:12)
- 8 celdas · pack 27,890 V

| Celda | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| mV | 3473 | 3486 | 3490 | 3480 | 3474 | 3489 | 3489 | 3488 |

- T1 30,1 °C · T2 29,8 °C

## PACK data → Status data (14:14)
- Protection status: 1 → **Fully**
- Indicación: DFET **On**; CFET Off; calefactor, ACin, pack power supply, shutdown, charger, límite de corriente: Off
- Control: limitación de corriente de carga **On** (marcha *high*), DFET **On**, CFET **On**, alarma LED **On**, test mode Off, zumbador Off
- Fallos: ninguno · alarmas: ninguna · balanceo: ninguna celda · certification failure: ninguno
