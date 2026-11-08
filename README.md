# Nexo Browser

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
- **Puentes nativos** (`nexo_bridge`, `nexo_translator`): solo aceptan mensajes de su propia
  extensión y del frame principal. El bridge no envía URL, título ni contenido de la página.
- **Privacidad**: el historial elimina parámetros sensibles (tokens, sesiones, firmas) antes de
  guardarse; las pestañas privadas no se guardan en historial, pestañas ni miniaturas; la
  notificación multimedia no muestra el título en la pantalla de bloqueo.
- **Descargas**: todas pasan por Gecko (cookies de la sesión, modo privado), se guardan en la
  carpeta privada de la app con nombres saneados, y se borran si quedan incompletas.

## Desarrollo
```bash
gradle :app:testDebugUnitTest   # tests unitarios (reglas de URL y nombres de archivo)
gradle :app:assembleDebug
gradle :app:lintRelease
```

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
