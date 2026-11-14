# PhantomShield 9.2 para Firefox y GeckoView

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
5. Elige qué analiza la IA (análisis detallado o solo señales básicas), acepta el envío de
   señales, activa la ayuda de IA y guarda. Si el navegador muestra un aviso de datos, acéptalo.
6. Pulsa **Probar conexión**: envía dos ejemplos inventados y dice si la clave, el modelo y la
   dirección funcionan, o el motivo exacto del fallo (clave rechazada, modelo inexistente…).

En los sitios autorizados se examinan candidatos después de cargar la página y cuando aparecen
elementos nuevos. **Analizar página** permite solicitar un análisis desde el popup. Si ya se
agotaron los cuatro lotes de esa página, hay que recargarla. La IA se aplica únicamente al documento
principal; los iframes siguen protegidos por las reglas locales, sin enviar su contenido a IA.

El cuerpo depende del protocolo:

- **Claude (Messages):** `model`, `max_tokens`, `system` y `messages`. No se envía `temperature`
  (los modelos actuales la rechazan) y se ignoran los bloques de razonamiento de la respuesta.
- **OpenAI:** `model`, `messages` y `max_completion_tokens`.
- **OpenRouter / compatible:** `stream: false`, `temperature: 0` y `max_tokens`; si el proveedor
  responde HTTP 400, se reintenta una vez con el cuerpo mínimo (`max_completion_tokens`) y se
  recuerda la variante aceptada.

Se envía la clave solo si existe. Activa el modo JSON únicamente si tu proveedor admite
`response_format: { type: "json_object" }`.
El modelo debe devolver, por ejemplo:

```json
{"decisions":[{"id":"c-1","isAd":true,"confidence":0.98}]}
```

El modelo solo puede clasificar identificadores de candidatos enviados. No se ejecutan código,
selectores ni URLs de su respuesta. Una confianza inferior a 0.8, un fallo de red o una respuesta
inválida conservan el contenido; las decisiones con identificadores desconocidos se ignoran. Tampoco se ocultan candidatos que cambiaron durante el análisis,
formularios, navegación o cuadros de diálogo. Al desactivar la IA o pausar el sitio, se restaura
el estilo de los elementos ocultados por IA. Los contadores existentes corresponden al bloqueo
de red y popups; el resultado de un análisis IA informa sus ocultaciones por separado.

### Privacidad, coste y velocidad

La IA está desactivada y sin sitios autorizados al instalar. Al habilitarla se envían al proveedor
el nombre del sitio y señales acotadas: tipo de elemento, etiquetas publicitarias normalizadas y
tamaños redondeados. Con **Análisis detallado** se añaden, por candidato, hasta 160 caracteres de
su texto visible (correos y números largos enmascarados), hasta tres dominios enlazados, el dominio
de un marco incrustado y hasta seis nombres de clase: es lo que permite al modelo decidir. No se
envían la página completa, HTML, formularios, cookies, capturas ni la URL completa de navegación. La clave se guarda en `browser.storage.local` del perfil del navegador,
sin sincronización, y se usa en las peticiones desde el background. No es un almacén cifrado de
secretos: protege el perfil del navegador. Si cambias la URL de API, debes introducir otra vez
la clave o borrarla para evitar enviarla por accidente a otro destino.

Las ventanas privadas no usan IA. El consentimiento es el interruptor del formulario de opciones;
el manifiesto declara contenido, actividad de navegación (dominio visitado) y autenticación cuando
se utiliza una clave. No hay telemetría añadida. Al actualizar se conserva la configuración de IA
(antes cada actualización la apagaba); cambiar el alcance o el nivel de detalle exige aceptar de nuevo.
La información completa está en [Privacidad](privacy/privacy.html), incluida dentro del paquete.

Las reglas de red y los filtros cosméticos siguen funcionando sin IA ni API. Las llamadas a IA
ocurren después, sin esperar dentro de `webRequest`, por lo que un anuncio nuevo podría verse
antes de ser clasificado. Hay un tiempo límite configurable de 1 a 30 segundos (12 por defecto), un
máximo de 12 candidatos por lote y cuatro lotes por documento. El background limita las llamadas sin caché a tres
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

## Cambios en 9.2: modelos de respaldo con cambio automático

- **Hasta dos modelos de respaldo**, además del principal. Cada uno tiene su
  proveedor, dirección, modelo y clave, y se pueden mezclar.
- **Proveedores incluidos:**
  - ChatGPT (OpenAI) y Claude (Anthropic);
  - OpenRouter, Gemini (Google), Groq, DeepSeek, Mistral y xAI;
  - cualquier API compatible con Chat Completions.
- **Cuándo se pasa al respaldo.** En la misma petición, si el principal no
  responde:
  - sin conexión o tiempo agotado;
  - error 5xx o 529;
  - límite o sin saldo (429/402);
  - clave o modelo rechazados;
  - solicitud rechazada después del reintento con el cuerpo mínimo;
  - respuesta ilegible o negativa a responder.
- **Proveedor que falla:** queda apartado un tiempo y se vuelve a probar
  solo.
  - Fallos de red o del servidor: de 20 s a 10 min, creciente.
  - Clave o modelo rechazados: 30 min, salvo que cambies los ajustes.
  - Respuestas ilegibles: 2 min.
  - `Retry-After`: aparta esa API entera.
  - Si todos están apartados, no se llama a ninguno y se explica el motivo.
- **Presupuesto:** cada intento cuenta en el presupuesto común. Un respaldo
  nunca dispara el gasto.
- **Claves:** cada una se envía solo a su propia API. Al cambiar la
  dirección de un respaldo hay que introducir su clave de nuevo.
- **Consentimiento:** añadir, quitar o cambiar un respaldo exige volver a
  aceptarlo.
- **En pantalla:**
  - opciones: tarjetas de respaldo con «Probar» y «Quitar», y el estado de
    cada proveedor (responde, motivo del fallo, tiempo de respuesta);
  - panel: «vía Respaldo N» cuando respondió un respaldo;
  - estadística `fallbacks`.
- **Código:** el cliente de IA se reescribió alrededor de una cadena de
  proveedores (`PhantomAIConfig.chain`), con salud por proveedor y pausas
  por API.

## Cambios en 9.1

**Menos bloqueos innecesarios**

- **Reglas `$badfilter`:** se respetan. Los autores de las listas las usan para
  desactivar reglas que rompían sitios. Antes se descartaban y la regla dañina
  seguía activa.
- **Detector de candidatos:**
  - `banner` y `promo` ya no cuentan como clases publicitarias. Las webs los
    usan para su portada, sus ofertas o el aviso de cookies.
  - Solo cuentan etiquetas cortas («Publicidad», «Patrocinado»), no frases ni
    enlaces de menú como «Publicar anuncio».
  - Un bloque con solo una clase sospechosa y todos sus enlaces dentro del
    propio sitio, o un artículo (`h1` o cuatro párrafos o más), ya no se envía
    a la IA.
- **Instrucciones de la IA:** dicen que los enlaces al propio sitio son
  contenido y que ocultar contenido real es peor que dejar pasar un anuncio.

**Más bloqueo donde corresponde**

- **Reglas `$important`:** bloquean aunque exista una excepción, como en
  ABP/uBO.

**Ventanas emergentes**

- Ya no se pueden abrir de estas formas:
  - un enlace creado en memoria y «pulsado» por un script (no pasaba por los
    oyentes de la página);
  - `contentWindow.open` de un iframe vacío;
  - formularios `target=_blank` enviados por script hacia otro sitio.
- Una ventana en blanco solo se abre tras tocar un control real, como pagar o
  imprimir. Antes bastaba tocar texto, y el anuncio se cargaba después en esa
  ventana.
- El control de clics está en la ventana, en fase de captura, para que un
  script no pueda detenerlo antes.
- Lista de redes de *popunder* ampliada y sincronizada entre el fondo y la
  página.

**Dominios publicitarios aprendidos con la IA** (`background/ai-domains.js`)

- **Qué se guarda:** cuando la IA o una regla local oculta un anuncio, el
  dominio de su marco o de su enlace de clic. Nunca la tienda anunciada.
- **Cuándo se bloquea:** pasa de «observando» a «bloqueado» al aparecer en
  anuncios de dos sitios distintos, o cuatro veces en uno.
- **Qué se bloquea:** las peticiones de terceros hacia ese dominio y las
  ventanas que una página intente abrir hacia él.
- **Qué no se bloquea nunca:**
  - páginas que abre el usuario;
  - recursos del propio sitio;
  - servicios comunes (Google, CDN, redes sociales, pagos);
  - dominios que ya bloquean las listas;
  - dominios que el usuario marcó «Nunca bloquear».
- **Señal para la IA:** los enlaces hacia un dominio aprendido son la señal
  `ad-network`, así que esos anuncios se resuelven sin gastar tokens.
- **Privacidad:** de cada sitio solo se guarda una huella con sal, nunca su
  nombre. No se aprende en privado.
- **Límites:** hasta 400 dominios. Caducan a los 30 días en observación y a
  los 90 bloqueados.
- **Ajustes:** interruptor «Aprender dominios de anuncios» (activado por
  defecto) y lista con «Olvidar», «Nunca bloquear» y borrado total. El panel
  muestra cuántos hay.

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
| `AI_DOMAINS_LIST` / `_FORGET` / `_ALLOW` / `_UNALLOW` / `_CLEAR` (`domain`) | `{ ok, domains, allowed, blocked, watching }`; solo desde opciones o panel |
| `AI_SCAN` (a la pestaña) | `{ ok, analyzed, hidden }` o un error; solicita analizar candidatos pendientes |

## Compatibilidad con GeckoView

1. WebExtension API estándar bajo Manifest V2, Gecko 142 o posterior.
2. `webRequest` con capacidades de bloqueo completas.
3. Almacenamiento local mediante `browser.storage.local`.
4. Alarmas periódicas para actualización de listas mediante `browser.alarms`.
5. Interfaz pensada para vistas embebidas y diálogos móviles.
