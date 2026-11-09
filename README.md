# Nexo Browser

Navegador Android en Kotlin/XML con GeckoView 159 (Firefox 159).

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
- PhantomShield 8.3.1 integrado, con bloqueo local y controles por sitio.
- Paneles de extensiones como hoja inferior redondeada y accesos directos en el menú.
- Diálogos de páginas: `<select>`, alert/confirm/prompt, fecha y hora, color, subida de
  archivos, inicio de sesión HTTP, "¿salir de la página?" y `navigator.share()`.
- Enlaces `target=_blank` y `window.open` en pestaña nueva; bloqueo de ventanas emergentes.
- Permisos de sitios con confirmación: ubicación, cámara/micrófono, DRM.
- Enlaces a apps (`tel:`, `mailto:`, `intent://`, Play Store…) con intent saneado.
- Puede ser el navegador predeterminado y recibir enlaces/texto compartido de otras apps.
- Buscar en la página.
- Sitios en modo oscuro cuando Nexo usa un tema oscuro.
- Tienda Android de addons.mozilla.org.
- Importación de XPI firmado e instalación por URL.
- Traducción de páginas en el dispositivo (ML Kit).
- Mini reproductor (Picture-in-Picture) y reproducción en segundo plano.

## Extensiones propias
Los XPI instalados dinámicamente se validan por GeckoView y requieren firma de Mozilla.
Una extensión privada/sin firma puede integrarse dentro de
`app/src/main/assets/extensions/` como extensión built-in y compilarse en el APK.

## Modelo de seguridad
- **URLs**: las reglas están centralizadas en `SafetyRules.kt` (`UrlSafety`).
  - Barra de direcciones y pantallas propias: `http`, `https`, `about`, `resource`, `moz-extension`.
  - Otras apps (Activity exportada): solo `http`/`https`. Las pantallas de Nexo usan el
    alias no exportado `InternalOpenActivity` (`MainActivity.openUrlIntent`).
  - Pestañas abiertas por extensiones: `http`, `https`, `moz-extension` y `about:blank`.
  - `javascript:`, `data:`, `file:`, `content:` e `intent:` nunca se cargan.
- **Extensiones**: la instalación automática al navegar solo acepta XPI de
  `addons.mozilla.org`. Los paquetes importados se validan (rutas, nº de entradas y tamaño
  real descomprimido) y el temporal se borra siempre.
- **Otras apps**: `tel:`, `mailto:`, `intent://`… se abren sin componente ni selector
  explícitos, solo con actividades BROWSABLE y sin permisos de URI heredados. Las
  redirecciones sin toque del usuario o en pestaña privada piden confirmación.
- **Permisos de sitios**: se pregunta siempre mostrando el dominio real; las notificaciones
  web se rechazan porque Nexo aún no las muestra.
- **Puentes nativos** (`nexo_bridge`, `nexo_translator`): solo aceptan mensajes de su propia
  extensión y del frame principal. El bridge no envía URL, título ni contenido de la página.
- **Privacidad**: el historial elimina parámetros sensibles (tokens, sesiones, firmas) antes de
  guardarse; las pestañas privadas no se guardan en historial, pestañas ni miniaturas; la
  notificación multimedia no muestra el título en la pantalla de bloqueo.
- **Descargas**: todas pasan por Gecko (cookies de la sesión, modo privado), se guardan en la
  carpeta privada de la app con nombres saneados, y se borran si quedan incompletas.

## Desarrollo
Requisitos: JDK 21, Gradle 9.8.2 y Android SDK con `platforms;android-37.2` y
`build-tools;37.0.0` (GeckoView 159 exige compilar contra la API 37.2; AGP 9.5).

```bash
node --test app/src/test/js/*.test.cjs  # regresiones de extensiones y mini reproductor
gradle :app:testDebugUnitTest   # tests unitarios (reglas de URL, buscador, nombres de archivo)
gradle :app:assembleDebug
gradle :app:lintRelease
```

Las librerías nativas van comprimidas en el APK (como Firefox para Android): el APK
arm64 pesa ~100 MB en vez de ~216 MB y Android las extrae una vez al instalar.

## CI y releases
El workflow `.github/workflows/main.yml` compila, ejecuta tests y lint en cada PR y push a `main`.

Para publicar: *Actions → Nexo Secure CI and Release → Run workflow → mode = release* desde `main`.
Requiere estos secrets del repositorio:

| Secret | Contenido |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore en base64 (`base64 -w0 nexo.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clave |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave |

La compilación corre sin acceso a esos secrets; solo el paso de firma los recibe. Si el tag
`v<versionName>` ya existe, el release se rechaza: sube `versionCode`/`versionName` primero.
Usa siempre la misma clave: un APK firmado con otra clave no se puede instalar encima.

La integración se documenta en [PhantomShield](docs/PHANTOMSHIELD.md).
Para conservar los datos al actualizar, consulta [actualizaciones y firma](docs/ACTUALIZACIONES-Y-FIRMA.md).
