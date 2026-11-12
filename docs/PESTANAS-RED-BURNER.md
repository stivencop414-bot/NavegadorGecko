# Grupos, red por pestaña y Burner (0.18.0)

## Grupos

En el selector de pestañas, **Grupos** crea, renombra o elimina grupos. Mantener
pulsada una miniatura permite moverla a un grupo existente o crear uno. El filtro
muestra todas, sin grupo o un nombre concreto; cerrar el grupo afecta únicamente
a las pestañas visibles de ese grupo y del modo normal/privado elegido.

Los nombres tienen hasta 40 caracteres. Las pestañas normales conservan el grupo
al restaurar la sesión; las privadas y Burner no se guardan. Un grupo organiza
pestañas, no crea por sí mismo un contenedor de cookies.

## Herramientas de la barra de direcciones

El botón de herramientas abre la configuración de la pestaña actual. Aplicar una
ruta o simulación recrea su sesión y recarga la página. Las pestañas hijas heredan
el perfil de su origen. La configuración de una pestaña normal se conserva en la
restauración; un perfil guardado inválido bloquea la carga hasta corregirlo.

Opciones de ruta:

- **Sistema** respeta la conexión del dispositivo, incluida una VPN ya instalada.
- **SOCKS5** usa el servidor y puerto indicados, con resolución DNS remota.
- **HTTP** usa un proxy HTTP indicado por el usuario, también para CONNECT HTTPS.
- **Tor mediante Orbot** propone `127.0.0.1:9050`; Orbot debe estar instalado,
  iniciado y configurado para escuchar en ese puerto. El campo sigue editable.

No hay servidor incluido, cliente VPN ni proceso Tor integrado. Esta versión no
configura autenticación de proxy. Sin un servidor real no se puede verificar la
salida; **Ver IP de salida** abre api.ipify.org en la pestaña para comprobarla.

El componente integrado `nexo_network` usa `proxy.onRequest`. Una página interna
asocia la política al identificador real de la pestaña antes de abrir el destino.
El puente acepta únicamente su propia extensión, sesión y documento principal.
Un fallo de preparación bloquea la pestaña; no continúa por conexión directa.
La cadena del proxy termina sin alternativa directa. Se evita la conexión
especulativa nativa de pestañas configuradas y Burner.

Mientras existe una ruta proxy se desactivan WebRTC y la predicción de red en
todo Nexo; las solicitudes web de fondo sin pestaña se bloquean, salvo las de
extensiones. Esto puede afectar otras pestañas. Las políticas se guardan solo en
`storage.session` para sobrevivir al reinicio del background, sin escribir las
identidades privadas en disco. Al terminar el proceso se pierden esas políticas;
las pestañas normales las reaplican desde su configuración al restaurarse.

El alcance es el tráfico web HTTP(S) atribuido por Gecko a esa pestaña. Buzón,
proveedores IA, listas de filtros y otros servicios de la app/extensiones usan
sus conexiones propias. La descarga que entrega la pestaña conserva su canal;
se bloquean las descargas e instalaciones que requerirían repetir la petición
fuera de una pestaña con proxy. Esto no garantiza aislamiento de todo el tráfico
del dispositivo. Hace falta validar la salida y los errores con un servidor real
en GeckoView Android antes de usarlo para navegación sensible.

## DNS y simulaciones

La API pública de GeckoView configura DoH para **todo el navegador**. El diálogo
lo indica: Sistema, Cloudflare, Google, Quad9 o una URL HTTPS personalizada sin
credenciales ni parámetros. Un Pi-hole UDP local necesita una pasarela DoH HTTPS;
no se ofrece DNS UDP distinto por pestaña. El modo estricto impide recurrir al DNS
del sistema cuando falla DoH. SOCKS5 usa resolución remota para su propia ruta.

El retardo de 0–2000 ms retrasa el inicio de cada solicitud HTTP(S) de la pestaña;
no emula ancho de banda, pérdida de paquetes ni latencia de cada paquete. Hay un
máximo de 256 solicitudes en espera. Las coordenadas simuladas sustituyen la API
de geolocalización para pruebas: no cambian la IP ni la ubicación deducida por un
servidor. Ambas opciones requieren recargar para aplicarse al documento nuevo.

## Identidades Burner

Burner se crea desde el menú (tarjeta **Burner**), la pulsación larga en el contador de
pestañas o Herramientas › Nueva identidad Burner, tras el primer consentimiento. En una
pestaña Burner, la pulsación larga en herramientas y la franja superior abren su buzón.
Cada identidad recibe un `contextId` aleatorio
y sesiones privadas. Sus ventanas hijas comparten contenedor, perfil y semilla.
Cerrar la última pestaña cierra sus paneles, elimina las credenciales en memoria
y solicita a Gecko borrar el almacenamiento de ese contexto. **Destruir identidad**
cierra todas sus pestañas. Las descargas y copias externas permanecen.

Si el componente interno no responde (5 s), un Burner **sin proxy ni simulaciones**
abre igualmente: el aislamiento de cookies y almacenamiento lo da Gecko con su contexto;
solo se pierde la variación de huella y se avisa. Con proxy o simulaciones se mantiene
el bloqueo: nunca se abre por conexión directa.

La variación de huella es experimental: una semilla por identidad y dominio altera
un bit de lecturas Canvas 2D y varía las cadenas de fabricante/renderizador de
WebGL. No toca velocidad de reproducción ni dimensiones del documento. No cubre
todas las APIs, llamadas anteriores a la respuesta de configuración, otros canales
WebGL ni exportaciones Canvas mayores de un millón de píxeles. Puede ser detectable
y no garantiza una huella única, coherente o anónima.

mail.tm crea un correo y su bandeja desde el propio Burner, con consentimiento
previo que identifica al proveedor. La cuenta y el token viven solo en memoria.
El buzón se abre al momento ("Creando…"), reintenta la creación ante cortes y se
actualiza solo cada 10 s mientras está abierto (como mucho 30 veces); muestra texto
plano, sin ejecutar HTML, imágenes remotas ni scripts de mensajes, y ofrece copiar
los códigos de verificación que detecta. Acepta las colecciones de la API en formato
`hydra:member`, `member` o lista simple. Sus peticiones HTTPS
usan una conexión propia, fuera del proxy de la pestaña. No se envían credenciales
a páginas web ni se incluyen en las copias/restauraciones.

Al cerrar se intenta eliminar la cuenta remota, incluso si la creación termina
después del cierre. El borrado remoto es de mejor esfuerzo: sin conexión, con un
error del proveedor, durante una creación incompleta o si Android mata el proceso
puede quedar una cuenta en el servicio sin credenciales recuperables en Nexo.
El proveedor controla la retención de datos. No debe usarse para correo sensible.

## Validación

Pruebas automatizadas cubren selección y rechazo de políticas, separación por tabId,
ausencia de alternativa directa, privacidad global, reinicio del background,
simulaciones, reglas de nombres e invariantes privadas. La API real de mail.tm se
probó creando, autenticando, consultando una bandeja vacía y eliminando la cuenta;
no se enviaron mensajes. Compilación y lint usan el SDK real de GeckoView 159.

Pendiente en teléfono: comprobar cookies entre identidades, limpieza del contexto,
buzón durante rotación/cierre, primer arranque del componente, salida y caída de
un proxy/Orbot real y convivencia con PiP. Las pruebas con mocks o Chromium no
sustituyen esas comprobaciones de GeckoView Android.
