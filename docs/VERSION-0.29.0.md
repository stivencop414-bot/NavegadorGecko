# Nexo 0.29.0

Base: `main` con 0.28.1. Nada de esto se ha validado aún en el teléfono
(POCO X6 5G, Android 16).

## Subtítulos

### Cortes de audio y tirones del video con la captura de Android

Causas probables (no confirmadas en el teléfono):

- **Redibujado constante:** el texto provisional de Deepgram cambia varias
  veces por segundo y cada cambio volvía a aplicar fondo, tamaño y líneas al
  subtítulo. Eso obligaba a recolocar toda la pantalla, también la vista del
  video.
- **Búfer de captura pequeño:** el búfer de 0,5 s se leía en bloques de
  250 ms.

Cambios:

- **Hilo principal:**
  - Los cambios se agrupan: se dibuja como mucho cada ~66 ms.
  - Solo se recoloca la pantalla si cambia el estilo.
  - El fondo se reutiliza en lugar de crearse en cada cambio.
- **Captura de Android:**
  - Hilo con prioridad de audio.
  - Búfer de 1 s.
  - Bloques de 100 ms.

**Si los cortes siguen,** compara con el interruptor CC › Más ajustes ›
«Audio compatible con la captura de Android» apagado.

### Menos retraso

- **Bloques de audio de 100 ms:** Deepgram recibe la voz ~150 ms antes.
- **`endpointing=250`** (antes 300): las frases se cierran antes.
- **Traducción anticipada del texto provisional:** desde 14 caracteres (antes
  24) y cada 450 ms (antes 700).

### Dos modelos de IA para traducir

- **Segundo modelo en paralelo:** con su propio proveedor, modelo, clave y
  dirección. Se encuentra en Ajustes de CC › Servicio de traducción › Modelo de
  IA.
- **Carrera:** se pide la traducción a los dos a la vez y se usa la primera
  respuesta válida; la otra se cancela. Si uno falla o se queda sin saldo, el
  otro sigue. Se gasta en ambos.
- **Proveedores:** ChatGPT, Claude, OpenRouter, Gemini, Groq, DeepSeek,
  Mistral, xAI o una API propia.
- **Hoja de CC › Estado:** muestra qué modelo ganó la última vez, cuánto tardó
  y el marcador (por ejemplo «gana 3–7»).
- **Código:** `FirstAnswer.race`.

### Diseño

- **Tamaño:** de Mínimo (11) a Muy grande.
- **Fondo:**
  - Oscuro.
  - Suave.
  - Sin fondo (con contorno oscuro para que se lea).
- **Color:** blanco o amarillo.
- **Longitud:** 1, 2 o 3 líneas. Fija también cuánto texto reciente se
  conserva.
- **Borrar al dejar de hablar:** 2, 3, 5 u 8 s.

### Subtítulos que se quedaban en pantalla

Cuando Deepgram detectaba el fin de una frase se programaba el borrado. Pero
durante el silencio Deepgram sigue mandando resultados vacíos, y cada uno
cancelaba ese borrado. Por eso el último subtítulo seguía visible minutos
después de que dejaran de hablar.

Ahora:

- Solo la voz reprograma el borrado.
- Al dibujar se comprueba además que la última voz sea reciente
  (`CaptionText.expired`).
- Con el texto que dibuja el reproductor, el de Nexo se retira si queda fijo
  mucho rato.

## PhantomShield 9.2

Ver `app/src/main/assets/extensions/phantomshield/README.md`:

- menos bloqueos innecesarios;
- ventanas emergentes;
- dominios aprendidos con la IA;
- modelos de respaldo con cambio automático.

## Validación en este entorno

- **Pruebas:** 101 Kotlin (6 nuevas en `CaptionsLatencyTest`) y 197 JS.
- **Compilación:** debug y release.
- **Lint:** debug y release.

**Pendiente en el teléfono:**

- fluidez con la captura de Android;
- retraso percibido;
- la carrera con dos claves reales;
- el diseño sin fondo sobre videos claros.

## 0.29.1: traducción sin el idioma original primero

### Qué pasaba

Google traducía por adelantado la frase a medias. Pero al llegar la frase
final esa traducción se descartaba, así que la frase se veía en inglés hasta
que respondía el traductor (Google, DeepL o IA). Por eso siempre salía
primero en inglés y luego traducida.

### Cambios

- **La traducción anticipada pasa a la frase final al instante**
  (`CaptionText.carry`):
  - Si corresponde a toda la frase, la frase ya sale traducida.
  - Si solo cubre su comienzo, se muestra eso y la parte nueva llega con la
    traducción final.
  - La traducción del servicio elegido la sustituye al responder.
- **«Mientras llega la traducción: Solo traducido»** (activado por defecto):
  el idioma original no se muestra nunca antes que su traducción. La opción
  «Mostrar el original» deja el comportamiento anterior.
- **La traducción anticipada funciona con todos los servicios:**
  - Antes no funcionaba con DeepL.
  - Con IA o DeepL, Google da la versión rápida y el servicio elegido la
    corrige.
  - Más frecuente: cada 300 ms desde 10 caracteres.
  - Se repite si la frase siguió creciendo.
  - Si Google limita las peticiones, se pausa 3 s.
- **Conexión con Google abierta al activar CC** (una petición HEAD, sin
  texto): la primera traducción no paga el saludo TLS.

### Pruebas

`CaptionsLatencyTest`, con 2 pruebas nuevas.
