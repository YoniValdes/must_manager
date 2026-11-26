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
    version: "0.23",
    items: [
      ["new", "Ayuda en cada tarjeta: toca el «?» de su esquina y un globo te explica qué mide y cómo leerla."],
      ["new", "Botón para compartir una captura de toda la app en buena calidad (por WhatsApp, Telegram…); se guarda también en Imágenes/MustManager."],
      ["imp", "Al cargar, la app dice de dónde viene la energía: del sol, de la red o de los dos (con un icono de torre eléctrica para la red)."],
      ["fix", "Las nubes del cielo ya no tapan el aviso rojo de «Sin corriente»."]
    ]
  },
  {
    version: "0.22",
    items: [
      ["new", "Icono nuevo: una M blanca sobre rojo, también en el título de la app."],
      ["imp", "Botones más claros: «Escanear» busca los BMS cercanos y cada uno de la lista tiene su propio botón «Conectar»."],
      ["fix", "Al girar la tablet de horizontal a vertical, la app ya no se queda estrecha y pegada a un lado."]
    ]
  },
  {
    version: "0.21",
    items: [
      ["new", "Actualizaciones con un botón: la app te avisa cuando hay una versión nueva y se actualiza sola, sin perder tus datos (en Acerca de puedes buscarla o desactivar el aviso)."],
      ["new", "Widget para la pantalla de inicio: SOC, consumo y autonomía sin abrir la app. Mantén pulsado un hueco de la pantalla de inicio → Widgets → Must Manager."],
      ["new", "Sin corriente: cuando se va la electricidad de la calle, la app muestra desde qué hora y cuántas horas llevas sin ella (también en el widget)."],
      ["new", "Alarma de batería baja: al 20 % suena como un despertador suave, aunque esté en No molestar. Se activa en la campana."],
      ["new", "Medidor de aparatos (botón del enchufe 🔌): mide cuánto gasta cada aparato de tu casa y mira hasta qué hora llegarías si lo apagas."],
      ["new", "Compatible con baterías de 48 V, como el Must HBP3300 TLV. La app reconoce sola tu batería (celdas, capacidad y kWh) y avisa si hay varias en paralelo."],
      ["new", "Ponle nombre a tu BMS (Casa, Taller…) con el lápiz ✏️ (en la lista o junto a «Conectado a…»), y ajusta en la campana la potencia del inversor y de tus paneles."],
      ["imp", "Gráfica de batería y consumo con zoom (pellizca o usa la barra de abajo), los apagones marcados («Se fue» / «Volvió») y la noche sombreada."],
      ["imp", "Autonomía más realista y estable: usa el consumo medio de los últimos 30 minutos, y el amanecer y el atardecer de cada día según tu provincia (elígela en la campana)."],
      ["imp", "Excel más cómodo (fecha y hora separadas, una columna por celda) y retoques visuales: la carga restante ahora es un depósito de agua que sube y baja (azul y con burbujas mientras carga), números con letra redondeada, gráfica de fondo en los indicadores, ventanas nuevas y más ordenadas, cielo renovado, mejor alto contraste y medidores de «Esta noche» más claros, que ya no se cortan ni se ponen en rojo sin motivo. El botón Atrás cierra las ventanas, y un consejo para que la app no deje de grabar («Mantener abierta», también en Acerca de)."]
    ]
  }
];
