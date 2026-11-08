# PhantomShield 8.3 para Firefox y GeckoView

WebExtension MV2 de bloqueo de solicitudes publicitarias, filtrado cosmético y clasificación IA opcional. Requiere Gecko/Firefox 142 o posterior por los permisos de datos utilizados también en Android. La integración de Nexo se prepara para una futura compilación del APK; no se ha validado aquí una app Android.

## Qué incluye

- Bloqueo de red mediante dominios incluidos y listas EasyList, EasyPrivacy y uBlock filters.
- Ocultamiento visual reversible: al apagar el bloqueo, la hoja de estilos deja de aplicarse.
- Eliminación de parámetros de rastreo en URLs de navegación.
- Detección de anuncios basada en pistas estructurales del DOM en páginas compatibles.
- Variación experimental de un píxel de Canvas, opcional y desactivada por defecto; no garantiza anonimato.
- Control por sitio persistente en almacenamiento local.
- Panel popup con diseño adaptado a pantallas móviles: interruptor general, pausa por sitio,
  contador de la página actual y total, actualización manual de listas y reinicio del contador.
- Clasificación opcional de candidatos publicitarios con una API Chat Completions compatible
  con OpenAI: proveedor, modelo, clave y sitios autorizados configurables.

## Configurar la IA

1. Abre el popup y pulsa **Configurar IA**. También puedes acceder desde las opciones de la extensión.
2. Introduce la URL base de tu proxy, por ejemplo `https://tu-proxy.example/v1`, y el identificador
   exacto de un modelo disponible en ese proveedor. También se acepta la URL completa que termine
   en `/chat/completions`. No se presupone OpenRouter ni un modelo específico.
3. Introduce tu clave API, si tu proxy la requiere. No se incluye ninguna clave en el código.
4. Añade los nombres de host exactos donde quieres usar IA, uno por línea; por ejemplo
   `www.ejemplo.com`. No se autorizan subdominios automáticamente.
5. Acepta el envío de señales, activa la ayuda de IA y guarda. Si Firefox solicita un permiso
   opcional de datos, concédelo para habilitar esta función.

En los sitios autorizados se examinan candidatos después de cargar la página y cuando aparecen
elementos nuevos. **Analizar página** permite solicitar un análisis desde el popup. Si ya se
agotaron los tres lotes de esa página, hay que recargarla. La IA se aplica únicamente al documento
principal; los iframes siguen protegidos por las reglas locales, sin enviar su contenido a IA.

La petición usa `POST /chat/completions`, `model`, `messages`, `stream: false`, `temperature: 0`
y `max_tokens: 800`. Se envía `Authorization: Bearer ...` solo si hay una clave. Activa el modo
JSON únicamente si tu proveedor admite `response_format: { type: "json_object" }`.
El modelo debe devolver, por ejemplo:

```json
{"decisions":[{"id":"c-1","isAd":true,"confidence":0.98}]}
```

El modelo solo puede clasificar identificadores de candidatos enviados. No se ejecutan código,
selectores ni URLs de su respuesta. Una confianza inferior a 0.95, un fallo de red o una respuesta
inválida conservan el contenido. Tampoco se ocultan candidatos que cambiaron durante el análisis,
formularios, navegación o cuadros de diálogo. Al desactivar la IA o pausar el sitio, se restaura
el estilo de los elementos ocultados por IA. Los contadores existentes corresponden al bloqueo
de red y popups; el resultado de un análisis IA informa sus ocultaciones por separado.

### Privacidad, coste y velocidad

La IA está desactivada y sin sitios autorizados al instalar. Al habilitarla se envían al proveedor
el nombre del sitio y señales acotadas: tipo de elemento, etiquetas publicitarias normalizadas y
tamaños redondeados. No se envían HTML, texto de página, formularios, cookies, capturas ni la URL
completa de navegación. La clave se guarda en `browser.storage.local` del perfil del navegador,
sin sincronización, y se usa en las peticiones desde el background. No es un almacén cifrado de
secretos: protege el perfil del navegador. Si cambias la URL de API, debes introducir otra vez
la clave o borrarla para evitar enviarla por accidente a otro destino.

Las ventanas privadas no usan IA. En Firefox con permisos de datos nativos, revocar el permiso
opcional desactiva también esta función. El manifiesto declara contenido, actividad de navegación
(dominio visitado) y autenticación cuando se utiliza una clave. No hay telemetría añadida.
Al instalar o actualizar se abre la página de privacidad y se desactiva IA hasta una nueva elección explícita.
La información completa está en [Privacidad](privacy/privacy.html), incluida dentro del paquete.

Las reglas de red y los filtros cosméticos siguen funcionando sin IA ni API. Las llamadas a IA
ocurren después, sin esperar dentro de `webRequest`, por lo que un anuncio nuevo podría verse
antes de ser clasificado. Hay un tiempo límite configurable de 1 a 15 segundos, un máximo de 12
candidatos por lote y tres lotes por documento. El background limita las llamadas sin caché a tres
por sitio y minuto y doce globales por minuto, con una sola llamada simultánea. La caché temporal
por sitio y señales dura diez minutos y se invalida cuando cambian los ajustes. No se promete
latencia de un proveedor concreto, eliminación universal de anuncios ni indetectabilidad.

Para distribuir la app, usa claves de cada usuario o un backend con autenticación, cuotas y
límites propios; nunca empaquetes una clave compartida del proveedor. Si tu app usa una política
de red, permite las descargas de EasyList/GitHub y el dominio de tu proveedor. Las listas incluidas
siguen funcionando sin descargar actualizaciones.

## Desarrollo y comprobaciones

No hay compilación de aplicación ni dependencias de ejecución. Con Node 20 o posterior:

```sh
node --test tests/*.test.cjs
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_*.py' -v
npx --yes web-ext@10.8.0 lint --config web-ext-config.cjs
npx --yes web-ext@10.8.0 build --config web-ext-config.cjs
```

La configuración de web-ext excluye pruebas y configuración del ZIP. Para probar en Firefox,
abre `about:debugging`, entra en **Este Firefox**, pulsa **Cargar complemento temporal** y
selecciona `manifest.json`. Las pruebas de integración requieren Firefox, geckodriver y
Selenium 4.40.0 en un entorno Python:

```sh
python tests/firefox-smoke.py --firefox /ruta/a/firefox --geckodriver /ruta/a/geckodriver --extension /ruta/a/phantomshield-8.3.0.zip
```

Esta prueba usa un perfil temporal y un proveedor IA simulado, sin enviar datos a un modelo real.
Verifica opciones, consentimiento nativo de Firefox, ocultación automática, conservación de
contenido editorial y formularios, restauración al pausar y bloqueo local cuando falla la API.
El identificador de modelo, la autenticación y el rendimiento del proveedor real se verifican
al configurar tus datos. La integración móvil requiere comprobar además la carga de la extensión,
la apertura de opciones y los permisos en la app anfitriona GeckoView; no se ha probado un APK.

## Paquete para Nexo y revisión de Mozilla

Un ZIP es el contenedor del código; cambiarle la extensión a XPI no lo firma. Esta entrega conserva
el ID Gecko existente y no cambia el estado de la ficha desactivada por Mozilla.

Para incluir la extensión en la próxima compilación Android, valida el paquete y prepara assets:

```sh
python3 tools/prepare_geckoview_assets.py phantomshield-8.3.0.zip /ruta/Nexo/app/src/main/assets/phantomshield --expected-id phantomshield@geckoview.internal --sha256 HASH_APROBADO
```

El destino debe ser nuevo. La herramienta exige un hash aprobado, comprueba identidad y rutas,
rechaza enlaces y limita descompresión. Un hash público no sustituye la autenticación del propietario.
La API oficial de extensiones integradas solo carga carpetas dentro del APK mediante
`resource://android/...`; no habilita importar ZIP arbitrarios desde Descargas con un PIN.

- [Integración privada en Nexo y límites del importador](docs/NEXO_PRIVATE_EXTENSIONS.md).
- [Informe, descripción propuesta y borrador opcional de revisión](docs/MOZILLA_REVIEW.md).
- [Políticas oficiales de complementos](https://extensionworkshop.com/documentation/publish/add-on-policies/).

## Cambios en 8.3

Retirados la suplantación de cabeceras, señuelos de anuncios, modificación de respuestas JSON,
acortadores, consulta DNS externa y módulos antiguos de anti-detección. Las sustituciones locales
coinciden con hosts y rutas reales. Canvas solo cambia sus métodos al activarlo y los restaura al
desactivarlo. Los bloqueos privados no se añaden al contador persistente. La fecha de actualización
completa solo se registra cuando todas las listas se descargan correctamente.

Se incluyen privacidad, rechazo explícito de IA y categorías de datos precisas. No se promete
indetectabilidad, eliminación universal de anuncios ni aprobación o firma de Mozilla.

## Estructura

- `background/`: motor de red, listas, actualizador, scripts locales de compatibilidad y cliente IA.
- `content/`: controles de página, filtrado cosmético, detector local de patrocinados y candidatos IA.
- `data/`: dominios y reglas locales, validación de configuración IA.
- `popup/`, `options/`, `privacy/`: interfaz y controles de datos.
- `web-accessible/`, `icons/`: scripts de compatibilidad y recursos locales.
- `tools/`, `tests/`, `docs/`: preparación del APK, pruebas y documentación; excluidos del ZIP instalado.

## Mensajes internos

| Mensaje | Respuesta |
| --- | --- |
| `GET_SITE_STATE` | `{ enabled, aiEnabled, host }` para la pestaña del remitente |
| `GET_STATS` (`tabId`, `host`) | `{ blockedCount, tabBlocked, siteDisabled, settings }` |
| `FORCE_UPDATE` | resultado por lista `[{ id, ok }]` |
| `RESET_STATS` | `{ ok, blockedCount: 0 }` |
| `AI_CLASSIFY` (`candidates`) | `{ ok, decisions: [{ id, hide }] }` solo desde el documento principal de un sitio autorizado |
| `AI_SCAN` (a la pestaña) | `{ ok, analyzed, hidden }` o un error; solicita analizar candidatos pendientes |

## Compatibilidad con GeckoView

1. WebExtension API estándar bajo Manifest V2, Gecko 142 o posterior.
2. `webRequest` con capacidades de bloqueo completas.
3. Almacenamiento local mediante `browser.storage.local`.
4. Alarmas periódicas para actualización de listas mediante `browser.alarms`.
5. Interfaz pensada para vistas embebidas y diálogos móviles.
