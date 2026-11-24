/*
 * Novedades de cada versión. Se muestran una vez en una ventana al abrir la app tras actualizar.
 *
 * AL PUBLICAR UNA VERSIÓN: añade una entrada nueva ARRIBA del todo, con el mismo número que
 * versionName en android/AndroidManifest.xml. Frases cortas, pensadas para el usuario (no técnicas).
 *   tipo "new" = Nuevo · "fix" = Corregido · "imp" = Mejorado
 *
 * Solo lo que el usuario nota respecto a la versión anterior publicada (no los arreglos internos
 * hechos durante el desarrollo). Unas 6–8 líneas; juntar lo parecido en una sola.
 */
window.CHANGELOG = [
  {
    version: "0.21",
    items: [
      ["new", "Widget para la pantalla de inicio: SOC, consumo y autonomía sin abrir la app. Mantén pulsado un hueco de la pantalla de inicio → Widgets → Must Manager."],
      ["new", "Alarma de batería baja: al 20 % suena como un despertador suave, aunque esté en No molestar. Se activa en la campana."],
      ["new", "Medidor de aparatos (botón del enchufe 🔌): mide cuánto gasta cada aparato de tu casa y guárdalo en una lista."],
      ["new", "Compatible con baterías de 48 V, como el Must HBP3300 TLV. La app reconoce sola tu batería (celdas, capacidad y kWh) y avisa si hay varias en paralelo."],
      ["new", "Ponle nombre a tu BMS (Casa, Taller…) con el lápiz ✏️ de la lista, y ajusta en la campana la potencia del inversor y de tus paneles."],
      ["imp", "Autonomía más realista y estable: usa el consumo medio de los últimos 30 minutos."],
      ["imp", "Excel más cómodo: fecha y hora en columnas separadas y una columna por cada celda de tu batería."],
      ["imp", "Retoques visuales: gráfica de fondo en los indicadores, ventanas nuevas, mejor alto contraste y medidores de «Esta noche» que ya no se cortan."]
    ]
  }
];
