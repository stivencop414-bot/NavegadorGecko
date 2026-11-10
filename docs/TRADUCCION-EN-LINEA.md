# Traducción dentro de la página (0.18.0)

El menú Traducir usa `TranslationsController.SessionTranslation` de GeckoView 159.
No navega a Google Translate ni crea otra pestaña. El motor modifica el documento
actual y permite restaurar el original al pulsar Traducir de nuevo. Los idiomas
ofrecidos son los que comunica Gecko; la detección regional se cruza con esa lista.

Antes de comenzar se confirma que Gecko puede descargar y almacenar los modelos
de idiomas desde Mozilla. El texto se procesa en el dispositivo. La traducción
puede requerir memoria, espacio y conexión para la primera descarga. El soporte
se comprueba con `isTranslationsEngineSupported`; no se simula éxito si no existe.

El selector consulta el tamaño real de descarga para el par de idiomas elegido.
Una respuesta antigua se descarta si cambia la selección, la página o la sesión;
si Gecko no comunica el tamaño se muestra como no disponible. La casilla de
descargas permite usar únicamente modelos ya guardados. El último idioma de
destino se conserva aunque no figure en la lista abreviada de Ajustes.

Durante una traducción puede solicitarse cancelar y restaurar el original desde
el mismo control. No se reemplaza la URL ni se provoca una recarga de la página.

Una navegación nueva, el cierre de pestaña o un cambio de sesión invalida la
solicitud pendiente: el diálogo no actúa sobre otra página. El texto traducido y
el estado transitorio no se guardan en el historial ni en TabStore.

La apertura de Google Translate en otra pestaña de 0.15.3 fue sustituida. Los
ajustes de idioma existentes se reutilizan cuando Gecko admite ese idioma.

Validación: compilación contra el SDK real, pruebas de selección regional de
idioma, y comprobaciones de que la ruta de traducción no llama a createTab ni
loadUri. Pendiente ejecutar descarga, traducción, restauración y navegación
simultánea en dispositivos GeckoView compatibles. No se afirma que esa prueba
física haya sido ejecutada en el entorno en la nube.
