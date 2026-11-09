# PhantomShield integrado en Nexo

Se incorpora el contenido de `phantomshield-8.3.0.zip` aportado por el propietario.
El ZIP interno del kit es idéntico. SHA-256 del archivo original:

`2955713c4359117d2db6be8379b443f9aed5dd6516113b3888a219d73afabce1`

Los archivos se validaron y extrajeron en `app/src/main/assets/extensions/phantomshield/`.
No se ejecuta un ZIP ni un actualizador de código en CI. El código de la extensión queda
versionado junto a la aplicación, conservando el ID
`phantomshield@geckoview.internal`.

### Cambios de integración en 8.3.1

Nexo 0.15.0 solo adapta el panel al móvil, sin tocar la lógica de bloqueo:
`popup/popup.css` ocupa el ancho del teléfono (antes 320 px fijos), usa la paleta de las
hojas de Nexo y sigue el tema claro/oscuro (`prefers-color-scheme`). El manifest pasa a
8.3.1 para que Gecko recargue los archivos al actualizar; el ID no cambia y se conservan
el almacenamiento y las excepciones por sitio.

### Modernización en 8.3.2

La base continúa siendo el paquete original, con cambios revisados en la interfaz y en la
planificación de los content scripts. El panel destaca los controles locales y el estado real
por sitio; las opciones de IA solo muestran los campos técnicos al activarlas. La IA sigue
requiriendo consentimiento y hosts exactos, y la clave guardada no se revela en el formulario.
La cabecera propia del panel sustituye la fila nativa duplicada en Nexo. La disposición,
colores, iconos y fuentes locales siguen el prototipo HTML aportado; los valores demostrativos
se sustituyen por datos reales.

Los indicadores de Nexo usan el badge estándar `browserAction`: total general y contador por
pestaña, más `OFF` y `PAUSE`. Se agrupan los cambios una vez por segundo, sin polling ni envío
de URL/título. Los bloqueos privados no incrementan el total persistente. Las cifras son
acumuladas desde el último reinicio del contador, no una estadística diaria inventada.

Los observadores de YouTube y los escaneos de IA se suspenden en pestañas ocultas o cuando
no están habilitados. Los lotes IA son acotados y se libera su observador al agotar el cupo.
No se modifican por ello las reglas de bloqueo ni se habilita IA automáticamente.

## Uso y actualización

`ExtensionManager` usa `ensureBuiltIn` con la URI
`resource://android/assets/extensions/phantomshield/` y registra los delegates habituales
para acciones, pestañas y opciones. Los paneles se muestran en una sesión popup separada,
sin convertirlos en la pestaña web activa; así los controles por sitio siguen leyendo la
página original. La navegación del panel reutiliza la política de URLs de extensiones.
Cerrar el panel libera su sesión; abrir opciones activa la pantalla del navegador. La lista espera el resultado de instalación y comunica
un fallo real si Gecko no consigue cargarla.

En **Extensiones → Mis extensiones → PhantomShield** se puede desactivar/activar, ejecutar
su panel en la pestaña actual y abrir sus opciones. La insignia «Integrada» distingue la
extensión incluida en el APK. Se actualiza con Nexo; no se ofrece desinstalarla del APK ni
buscar una actualización en AMO. No se borra su almacenamiento al iniciar.

Los bloqueos locales funcionan sin una API de IA. La IA, la variación de Canvas y el acceso
a navegación privada mantienen las elecciones y permisos de la extensión; no se introduce
ninguna clave API ni se aceptan permisos opcionales automáticamente. La IA viene desactivada
y requiere consentimiento, configuración y autorización por sitio. El paquete incluye su
propia página de privacidad y README.

Las listas de filtros se descargan desde `easylist.to` y `raw.githubusercontent.com`.
El código de la extensión se actualiza mediante un APK revisado, no descargando JavaScript.
La integración built-in no necesita firma de Mozilla y no modifica la verificación de firmas
para XPI importados. Tampoco cambia una posible resolución de bloqueo/revisión de Mozilla.

## Pruebas

- Tests Node con el background real: bloqueo local sin red, apagado general/excepción por
  sitio, contadores privados, ausencia de llamadas IA por defecto y recursos declarados.
- Targets Android habituales: tests JVM, assembleDebug/Release, lintDebug/Release.
- Inspección de los APK: los recursos empaquetados deben coincidir con los assets versionados.
- Pendiente en dispositivo GeckoView: primera instalación, reinicio, conservación de una
  excepción por sitio al actualizar, panel/opciones, permisos opcionales y YouTube/PiP con
  PhantomShield activado y desactivado. No se afirma que esas pruebas en Android se hayan ejecutado.
