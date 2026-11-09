# Interfaz basada en el prototipo del propietario

Nexo 0.15.1 toma como referencia visual `Prototipo UI_UX Nexo y PhantomShield.html`:
fondo Midnight `#090A0F`, superficies `#12141D`/`#191C28`, acento esmeralda en PhantomShield,
emblema N con degradado violeta/índigo/cian, tarjetas y accesos en cuatro columnas.
La dirección queda debajo del contenido, encima de la navegación. Inicio y menú son
controles Android; el panel y las opciones son páginas de la extensión.

Los controles funcionan con datos de Nexo y PhantomShield: añadir/mostrar accesos, abrir
pestañas privadas, habilitar la extensión, pausar por sitio, reiniciar contadores con
confirmación y configurar IA con consentimiento. Las cifras de ejemplo, barras de estado
del teléfono y controles de la presentación HTML no se incorporan a la app.

Los contadores muestran el total desde su reinicio o el de la pestaña activa. Si Gecko aún
no ha publicado el estado, se muestra una indicación neutra. No se inventan estadísticas
diarias. La IA es opcional; el bloqueo local no necesita un modelo ni una clave.

Se conservan los temas claro y OLED y las etiquetas accesibles. En la interfaz nativa,
los brillos se representan con degradados y superficies estáticas, para evitar trabajo de desenfoque
o animaciones permanentes. La comparación visual nativa y las pruebas de YouTube/PiP
requieren un dispositivo Android; las capturas de Chromium solo validan las páginas web
de PhantomShield.

## Fuentes locales

Inter y JetBrains Mono se distribuyen bajo SIL Open Font License 1.1. Los TTF proceden
del repositorio oficial Google Fonts:

- [Inter](https://github.com/google/fonts/tree/main/ofl/inter)
- [JetBrains Mono](https://github.com/google/fonts/tree/main/ofl/jetbrainsmono)

Android usa los TTF en `res/font`. Las páginas de PhantomShield usan las conversiones
WOFF2 de esos mismos archivos completos, sin cargar servicios externos. Las licencias
se incluyen en `assets/licenses` y en el directorio `phantomshield/fonts`.
