# IA, memoria y diagnóstico (Nexo 0.20.0 / PhantomShield 8.8.0)

En **PhantomShield → IA y memoria**, configura proveedor, modelo, consentimiento
y alcance, prueba la conexión y guarda. Para recordar resultados, activa
**Recordar decisiones de IA para futuras visitas** y guarda. Viene apagado para
conservar la elección de almacenamiento de los perfiles existentes.

El panel diferencia elementos ocultados por reglas locales, modelo, memoria
persistente y caché temporal. El estado del proveedor muestra errores reales;
una pasada sin candidatos ya no afirma que la página esté libre de anuncios.
Las solicitudes que usan el proveedor dependen de una API configurada y pueden
consumir saldo. No se incluye una clave ni se simula una llamada exitosa.

## Qué se aprende

La memoria usa el almacenamiento local de la extensión. Guarda dominio, huella
SHA-256, fecha de aprendizaje, último uso y cantidad de reutilizaciones. No guarda
texto, HTML, rutas, selectores ni credenciales. El contexto local de cada candidato
(identificadores, clases, un fragmento de texto y señales) participa en la huella,
pero no se añade al envío al proveedor en modo básico. Así una decisión no se
reutiliza solo porque dos tarjetas tengan el mismo tamaño. El modo detallado
conserva su consentimiento y envío acotado habituales.

Se guardan decisiones positivas del modelo con confianza declarada ≥0,95, al
menos dos señales y contexto local. No se aprende de reglas locales ni de la
prueba de conexión. Una huella cambiada requiere nuevo análisis. No se aplican
reglas genéricas de bloqueo a todo un dominio ni se ejecutan instrucciones del
modelo. La confianza no garantiza exactitud: las correcciones siguen disponibles.

Límites: 1000 entradas totales, 50 por host y 30 días desde su aprendizaje, sin
renovar esa caducidad al reutilizar. Se conservan las más recientemente usadas
al superar capacidad. Las entradas caducadas no se aplican y se depuran en el
siguiente arranque o escritura. La memoria es opcional, local y sin sincronización;
no existe una base compartida con otros usuarios.

## Corregir decisiones

- **Olvidar** elimina las huellas del host seleccionado y la caché temporal del
  clasificador. Restaura lo ocultado por este detector en sus páginas abiertas y
  pausa su escaneo hasta analizar manualmente o navegar. Las reglas de bloqueo
  independientes conservan su estado. Una nueva visita puede aprender otra vez.
- **Excluir de IA** olvida ese host y lo añade a las exclusiones, que también
  cubren subdominios. Para volver a autorizarlo, quítalo de la lista de exclusiones
  y guarda; no reaparecen las huellas borradas.
- **Borrar toda la memoria** requiere confirmación dentro de los ajustes. No
  borra la clave, configuración o contadores. Los controles siguen disponibles
  aunque se apague IA.

Apagar IA, retirar consentimiento, pausar la protección o excluir un dominio
impide usar tanto el proveedor como la memoria. Las pestañas privadas no la
leen ni la alimentan. El gestor se limita a páginas normales de la propia
extensión. Si falla el almacenamiento, se muestra el problema; no se afirma
que se haya borrado o guardado correctamente.

## Correcciones del análisis

Un lote mixto conserva decisiones conocidas aunque los candidatos restantes
encuentren un proveedor ocupado, un límite o un error. Los pendientes no se
marcan como analizados; se reintentan de forma acotada cuando corresponde.
Los cambios de configuración y el borrado invalidan respuestas en curso, para
que una respuesta tardía no reconstruya lo eliminado. Los presupuestos globales
no se reinician al cambiar ajustes ni al borrar memoria.

Un iframe de terceros con dimensiones publicitarias ya no se oculta por esa
combinación sin consultar el modelo. Se mantienen exclusiones de reproductores,
formularios, Canvas y PiP. La caché temporal también distingue el contexto local
en modo básico, evitando compartir decisiones entre tarjetas distintas.

## Validación

Las pruebas Node cubren persistencia, aislamiento por dominio/contenido,
privado y exclusiones, caducidad, capacidad, fallos de disco, correcciones durante
una llamada pendiente y lotes parcialmente resueltos. Las respuestas del proveedor
se simulan: no se usaron claves ni llamadas facturables reales.

Pruebas de navegador con Playwright instalado y Chromium:

```sh
python3 -m http.server 8780 --bind 127.0.0.1 --directory app/src/main/assets/extensions/phantomshield
# En otra terminal:
node tools/check_ai_ui.cjs
node tools/check_ai_dom.cjs
```

`NEXO_CHROMIUM` cambia el ejecutable; `NEXO_TEST_ORIGIN` cambia el origen del
servidor de UI. Se comprueban formularios, exclusión sin sobrescribirla al guardar,
búsqueda/borrado, temas y anchos 320/390/600. La prueba DOM ejecuta el clasificador
y memoria reales con un proveedor simulado: aprende, reutiliza sin red, restaura
al olvidar y respeta video/PiP. Las capturas quedan en `app/build/reports/browser`.

Queda pendiente la prueba con la API elegida por el usuario y un teléfono GeckoView;
Chromium y las pruebas JVM no sustituyen esa comprobación.
