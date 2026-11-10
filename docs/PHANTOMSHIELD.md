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

### Diseño e IA en 8.3.3

El HTML de referencia se adapta a vistas Android: paleta azul medianoche, acento cobalto
(disponible junto a los colores anteriores), inicio alineado a la izquierda y dock inferior
redondeado. Panel y opciones comparten superficies y colores; verde indica protección.
Se conservan las fuentes locales para no descargar tipografías durante la navegación.

En Privacidad e IA elige **Automático en todos los sitios públicos** para evitar registrar
cada dominio, o conserva **Solo sitios elegidos**, con subdominios opcionales. Cambiar el
alcance requiere renovar el consentimiento. Las exclusiones se aplican también a sus
subdominios; el panel ofrece excluir el sitio actual y apagar la IA globalmente.
Las configuraciones existentes conservan el modo de dominios exactos. IA sigue apagada
por defecto y en navegación privada. El filtro de nombres locales es conservador y no
resuelve DNS: para sitios sensibles usa exclusiones o el modo de sitios elegidos.

El modelo revisa candidatos de anuncios con señales normalizadas; no recibe toda la
página ni cada solicitud de red. Las reglas locales siguen actuando inmediatamente.
Se comparten caché y presupuesto entre pestañas: por defecto 6 peticiones/minuto y 60/hora,
configurables hasta 12/minuto y 120/hora, con 3 por sitio/minuto y 3 lotes por página.
Los presupuestos duran mientras se ejecuta el background, no sustituyen límites de gasto
del proveedor. Ante 429/5xx se aplica una pausa global y Retry-After acotado.
Cambiar ajustes cancela solicitudes en curso y no reinicia presupuesto ni pausa.
Los cambios de clases, atributos publicitarios y etiquetas en contenedores existentes
vuelven a entrar en la cola acotada de análisis, sin recorrer toda la página por mutación.

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
y requiere consentimiento, configuración y alcance autorizado. El paquete incluye su
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

### Proveedores y contadores en 8.4.0

Las opciones incluyen presets OpenAI Chat Completions, Claude Messages y
OpenRouter. La URL sigue editable dentro de una sección avanzada cerrada; la
selección del protocolo cambia la ruta, autenticación y lectura de respuesta.
Claude utiliza `x-api-key`, `anthropic-version` y bloques `content`; OpenAI y
OpenRouter usan Bearer y `choices`. Cambiar de proveedor revoca el consentimiento
del formulario, conserva la clave sin mostrarla y exige una clave nueva si
cambia el endpoint. No se envía la clave anterior a otra dirección automáticamente.

Los contadores persistentes son agregados: solicitudes intentadas, tokens de
entrada/salida que reporta la API, respuestas sin uso, candidatos analizados y
marcados (umbral >= 0.95, incluidas decisiones reutilizadas). No representan
anuncios únicos ni coste. Las peticiones privadas siguen rechazadas antes de
consultar al proveedor o incrementar contadores. Una API sin `usage` no genera
estimaciones. Las pruebas de protocolo usan respuestas simuladas, sin claves
ni consultas facturables a proveedores reales.

El manifest usa `options_ui.open_in_tab=false` para delegar la apertura a la app.
La app reutiliza una pestaña de opciones existente, espera a que termine el
mensaje del popup para cerrarlo y no pierde el contexto de la página original.
Esta ruta debe comprobarse también en un dispositivo GeckoView.

### Bloqueo real y IA funcional en 8.5.0

**YouTube.** Antes el anuncio solo se silenciaba y aceleraba. Ahora `content/page-hooks.js`
(mundo MAIN) elimina `adPlacements`, `playerAds`, `adSlots` y `adBreakHeartbeatParams` de
`ytInitialPlayerResponse`, de `JSON.parse`, de `Response.json()` y de XHR con respuesta JSON,
y quita los anuncios de la secuencia de Shorts. Las funciones se envuelven con `Proxy` para
conservar su apariencia nativa. Si un anuncio empieza igualmente, se silencia, se pulsa omitir
o se salta a su final. El fondo bloquea `/pagead/`, `/api/stats/ads`, `/get_midroll_`,
`/youtubei/v1/player/ad_break` y `/ptracking`. Respeta la pausa por sitio.

**Ocultación.** Nuevo `background/cosmetic-engine.js`: aprovecha las reglas `##` de EasyList y
uBlock que antes se descartaban. Reglas por sitio y genéricas complejas se aplican siempre; las
~13.000 genéricas simples solo cuando su clase o id aparece en la página (consulta por lotes
desde `content/cosmetic-filter.js`). Se respetan `#@#`, `$generichide` y `$elemhide`; los filtros
procedurales y scriptlets se ignoran. El CSS se inserta con `tabs.insertCSS` (origen de usuario,
inmune a la CSP del sitio); si no está disponible, se usa un `<style>` en la página.

**Red.** Listas incluidas ampliadas (228 servidores de anuncios, 84 rastreadores) para proteger
antes de la primera descarga. Catálogo único con espejos: EasyList y EasyPrivacy usan
`easylist.to` y, si falla, `raw.githubusercontent.com/uBlockOrigin/uAssetsCDN`; se añaden
`uBlock privacy`, `uBlock unbreak` y EasyList Spanish (opcional). Las páginas que abre el
usuario ya no se cancelan (solo redes de ventanas emergentes).

**Rendimiento.** Las expresiones regulares se compilan al primer uso y los dominios simples se
guardan en un conjunto: con las listas reales (106.000 reglas de red, 31.000 cosméticas) el
motor pasa de ~100 MB a ~20 MB de memoria y carga en ~0,5 s.

**IA.** Causas por las que no funcionaba y su corrección:

- Cada actualización de la app la apagaba y revocaba el consentimiento (`onInstalled`). Ahora se
  conserva la configuración.
- Se enviaba `temperature: 0` y `max_tokens: 800`: los modelos Claude actuales rechazan
  `temperature` y los de razonamiento agotaban la salida; los modelos OpenAI recientes exigen
  `max_completion_tokens`. Cuerpo por protocolo y reintento con cuerpo mínimo tras HTTP 400.
- Tiempo límite de 5 s, corto para redes móviles: 12 s por defecto, hasta 30 s.
- Cualquier fallo mostraba el mismo mensaje. Ahora se clasifica (clave, modelo, solicitud,
  límite, red, tiempo, formato), se guarda en `aiStatus` y se muestra en panel y opciones;
  **Probar conexión** lo verifica con ejemplos inventados.
- Un fallo del aviso nativo de permisos impedía guardar. Ahora solo bloquea una negativa
  explícita.
- El modelo solo recibía etiqueta, señales y tamaño, y hacía falta 0,95 de confianza: casi
  nunca ocultaba nada. El **análisis detallado** (opcional, con consentimiento renovado) añade
  texto breve saneado, dominios enlazados, dominio del marco y clases; se detectan también
  banners enlazados y marcos de terceros con tamaño de anuncio; umbral 0,8.

Los anuncios de video de YouTube se resuelven con reglas locales, no con IA.

Verificado con tests Node (motor, fondo, YouTube, IA) y con los scripts reales en Chromium
(detección de candidatos, ocultación, poda de YouTube por las cuatro vías). Pendiente en un
teléfono con GeckoView: `tabs.insertCSS`, el mundo MAIN en m.youtube.com con anuncios reales
y una llamada real al proveedor de IA.
