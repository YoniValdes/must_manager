/*
 * Novedades de cada versión. Se muestran una vez en una ventana al abrir la app tras actualizar.
 *
 * AL PUBLICAR UNA VERSIÓN: añade una entrada nueva ARRIBA del todo, con el mismo número que
 * versionName en android/AndroidManifest.xml. Frases cortas, pensadas para el usuario (no técnicas).
 *   tipo "new" = Nuevo · "fix" = Corregido · "imp" = Mejorado
 */
window.CHANGELOG = [
  {
    version: "0.21",
    items: [
      ["new", "Compatible con baterías de 48 V (16 celdas), como el Must HBP3300 TLV. Antes decía «valores fuera de rango» y no mostraba datos."],
      ["new", "La app reconoce sola tu batería: número de celdas, capacidad y kWh."],
      ["new", "Aviso si tienes varias baterías en paralelo (por ahora solo se muestra la batería 1)."],
      ["new", "Ajustes nuevos en la campana: potencia del inversor y de tus paneles (para que el aviso de «llegó la corriente» no salte por el sol)."],
      ["new", "Esta ventana de novedades después de cada actualización."],
      ["fix", "Los medidores de «Esta noche» ya no se cortan ni se deforman en vertical o al girar la pantalla."],
      ["imp", "El color del voltaje se calcula por celda, así vale para cualquier batería."],
      ["imp", "Al exportar a Excel salen tantas columnas de celdas como tenga tu batería."],
      ["imp", "«Acerca de» ahora es una ventana centrada con botón Aceptar."]
    ]
  }
];
