# Traducción de páginas sin modelos locales

Nexo 0.15.1 sustituye el traductor ML Kit por la función pública de traducción de sitios de
Google Translate. No utiliza una API no documentada ni una clave compartida: abre
`https://translate.google.com/translate?sl=auto&tl=<idioma>&u=<dirección codificada>`.
La opción no necesita contratar una API; la disponibilidad y las restricciones dependen del
servicio de Google. No se promete traducción ilimitada ni compatibilidad con todas las webs.

## Uso

Desde el menú, elegir **Traducir** y confirmar que se comparte la dirección de la página con
Google. La traducción abre otra pestaña y conserva la original, también si es privada. La
confirmación avisa expresamente al usar navegación privada. El destino se elige en Ajustes;
se conserva la preferencia anterior de idioma.

No se extrae el DOM ni se ejecutan modelos locales. Las páginas con inicio de sesión, formularios
privados o contenido dinámico pueden no funcionar en el servicio externo. Se bloquea el envío
de páginas internas, direcciones de red local, URLs con usuario/contraseña o parámetros conocidos
de tokens/sesión. Este filtro reduce riesgos; no identifica todos los posibles datos privados en
una URL, por lo que la confirmación del usuario sigue siendo necesaria. Si la página cambia
mientras se confirma, la operación se cancela. No se traduce de nuevo una página del propio
servicio.

## Migración y peso

Se retiran las dependencias `com.google.mlkit:translate` y `com.google.mlkit:language-id`, la
extensión de traducción local y su puente de mensajes. Al actualizar se intenta desinstalar la
extensión antigua del perfil Gecko. No se borra el historial, las pestañas ni otros datos del
perfil. No se garantiza borrar modelos antiguos que Google Play Services ya haya almacenado;
la nueva versión deja de cargarlos y solicitarlos. La compresión de bibliotecas de 0.15.0 se
mantiene y no cambia targetSdk, permisos, applicationId ni la clave persistente de firma.

## Validación

Las pruebas JVM verifican codificación y conservación de URLs públicas, rechazo de esquemas
internos/red local/credenciales y validación del idioma. Se comprobó una petición de la función
pública con `example.com`: HTTP 200 y redirección al dominio `example-com.translate.goog` con
`_x_tr_tl=es`. Esto verifica el acceso al servicio y el destino; no garantiza su calidad lingüística.
La confirmación, navegación y resultado final requieren comprobarse también en Android.

La modernización toma como referencia el prototipo HTML aportado por el propietario. Las
fuentes Inter y JetBrains Mono se incluyen localmente, con sus licencias OFL; no se cargan
Tailwind, Google Fonts ni los contadores de demostración del prototipo en tiempo de ejecución.
