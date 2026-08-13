# Nexo Browser v6

Navegador Android en Kotlin/XML con GeckoView 153.

## Incluye
- Omnibox: URL o búsqueda por palabras.
- Google, DuckDuckGo, Bing, Brave Search y Startpage.
- Multipestañas, sesiones LRU y pestañas privadas.
- Modo escritorio: User-Agent + viewport.
- Temas Midnight, OLED y claro.
- Seis colores de acento.
- Historial, descargas y compartir.
- Limpieza de datos.
- Administrador de extensiones.
- Tienda Android de addons.mozilla.org.
- Importación de XPI firmado e instalación por URL.
- WebExtension integrada con Native Messaging bidireccional.

## Extensiones propias
Los XPI instalados dinámicamente se validan por GeckoView y requieren firma de Mozilla.
Una extensión privada/sin firma puede integrarse dentro de
`app/src/main/assets/extensions/` como extensión built-in y compilarse en el APK.
