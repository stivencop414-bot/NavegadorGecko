# Transferencia entre los paquetes de Nexo

## Paso automático (0.26.1)

Desde 0.26.1 los datos nativos pasan solos de la app anterior (`com.ejemplo.navegador`) a Nexo nuevo
(`com.nexo.browser`), sin archivos ni contraseñas:

1. Instala `Nexo-anterior-actualizacion-v0.26.1-…apk` encima de la app anterior. Es una actualización del
   mismo paquete y con la misma firma: conserva todos sus datos. Al abrirla avisa de la mudanza.
2. Instala `Nexo-v0.26.1-…apk` y ábrelo. Ofrece **Traer datos** (también en Ajustes › Traer datos de Nexo anterior).
3. Se traen historial (se combina, una entrada por dirección), accesos rápidos fijados y ocultos, pestañas
   normales que no estén ya abiertas, ajustes conocidos y claves Deepgram/DeepL/IA. Una clave que ya
   tengas puesta en la app nueva no se sobrescribe. La app anterior no se modifica.
4. Comprueba los datos y solo entonces desinstala la app anterior.

Cómo se protege: la app anterior expone `LegacyProfileProvider` (autoridad `com.ejemplo.navegador.profile`)
solo en la compilación de transición. Su `call()` comprueba que quien llama es `com.nexo.browser`, que
está firmado con la misma clave (`checkSignatures`) y que tiene el permiso de nivel `signature`
`com.nexo.browser.permission.TRANSFER_PROFILE`. Las claves viajan por Binder entre esas dos apps,
descifradas con el Keystore del origen y cifradas de nuevo con el del destino; nunca se escriben en un
archivo intermedio. El destino valida todo (versión, origen, tipos, URL web) antes de aplicar nada.
`tools/check_apk_identity.py` comprueba en la CI que el proveedor solo está activo en el paquete anterior.

No incluye pestañas privadas, Burner, cookies, descargas, miniaturas ni datos de extensiones
(configuración y memoria de PhantomShield, storage de otras extensiones). Para eso sigue disponible la
copia cifrada manual descrita abajo (Ajustes › Copia cifrada del perfil).

## Copia cifrada manual (incluye datos de extensiones)

## Estado y preparación

Este flujo requiere una comprobación en Android antes de distribuir una actualización de transición. Las pruebas JVM de archivo y recuperación no demuestran que Gecko pueda abrir los almacenes copiados en un dispositivo. No desinstales ninguna de las dos aplicaciones hasta verificar los datos y conservar una copia cifrada externa.

La compilación normal usa `com.nexo.browser`; `-PnexoTransition` usa `com.ejemplo.navegador`. Ambas comparten las fuentes/namespace `com.nexo.browser` y la misma versión. El APK de transición **debe firmarse por el responsable de distribución con la misma clave de la aplicación antigua** para actualizarla. Un debug APK o una firma distinta no puede actualizar una instalación release; no desinstales para sortearlo. Este PR no firma ni publica actualizaciones.

Los nombres completos de componentes apuntan a las fuentes nuevas. FileProvider usa `${applicationId}.files`. Los IDs `nexo_bridge@com.ejemplo.navegador`, `nexo_network@com.ejemplo.navegador` y `phantomshield@geckoview.internal` son identidades de extensiones, independientes del paquete Android; se conservan para no perder sus almacenes.

## Exportar e importar

1. Instala la actualización de transición sobre el paquete antiguo conservando su firma y datos. Abre Nexo normalmente al menos una vez.
2. Ajustes → Transferir perfil entre paquetes → Cerrar y preparar. Se persisten pestañas normales, se esperan las escrituras nativas y se solicita `GeckoRuntime.shutdown()`. Solo su callback `onShutdown` marca el perfil como cerrado limpiamente; después se termina el proceso. Vuelve a abrir Nexo.
3. En mantenimiento, introduce una contraseña de 12–256 caracteres y pulsa Exportar copia cifrada. El selector de documentos crea `Nexo-perfil.nexo`. Espera al mensaje de finalización. Un archivo de una exportación interrumpida no es válido: vuelve a exportar a otro documento.
4. Conserva el archivo y la contraseña por separado. No hay recuperación de contraseña ni protección contra una contraseña fácil de adivinar. La copia contiene claves de servicios: guárdala como un secreto.
5. Abre normalmente el paquete nuevo una vez para crear su perfil; entra en mantenimiento del mismo modo. Selecciona Importar y reemplazar datos e introduce la contraseña. Se autentica el archivo entero, se valida el manifiesto y el esquema, y se prepara todo antes de escribir el destino.
6. Espera al mensaje de importación aplicada. Sal del mantenimiento y vuelve a abrir Nexo. Los procesos y cachés antiguos no sobreviven a esa salida.
7. En Extensiones → Reinstalar extensiones transferidas, revisa cada XPI. Se usa el instalador normal: Gecko comprueba firma, identidad y permisos y solicita confirmación. No se importan `signedState`, permisos concedidos, ni `extensions.json`. PhantomShield y los otros componentes integrados vienen con la aplicación.
8. Verifica historial, grupos, accesos, ajustes, claves y datos de cada extensión. Conserva el paquete antiguo y la copia exportada. Para otra importación debes restaurar el respaldo anterior o eliminarlo mediante la confirmación explícita del mantenimiento.

Si el cierre de Gecko no terminó, exportar/importar queda bloqueado. Sal de mantenimiento, abre Nexo normalmente y vuelve a preparar la transferencia. No se toma una instantánea de un Gecko en ejecución.

## Alcance de los datos

Incluye preferencias nativas tipadas y conocidas, historial, accesos, URL/título/grupo/modo escritorio de pestañas normales y claves nativas Deepgram/DeepL/IA de traducción. No se conserva el historial opaco de navegación de cada pestaña ni rutas de proxy por pestaña. Las claves se descifran en origen y se cifran de nuevo con el Keystore del destino, dentro de la importación autenticada. Las claves del Android Keystore nunca se copian. Si una clave del origen no puede descifrarse, la exportación falla en lugar de omitirla silenciosamente.

No incluye descargas, miniaturas, pestañas privadas, identidades/pestañas Burner, cookies de sitios, sesiones web, caché de sitios ni el perfil completo. Los datos persistentes de extensiones son datos de esas extensiones; un formato arbitrario no permite deducir su contenido semántico. Los orígenes privados y los contextos de contenedores se excluyen por principal.

El perfil se resuelve desde `files/mozilla/profiles.ini`, con una única selección relativa verificable. No se adivina un nombre de carpeta. Perfiles ambiguos/externos y enlaces simbólicos se rechazan. Este mecanismo corresponde al arranque por defecto del repositorio; un futuro perfil personalizado exige adaptar y validar el resolvedor.

De los UUID de `extensions.webextensions.uuids` se seleccionan únicamente:

- `storage/default/moz-extension+++<uuid>`: IndexedDB y otros datos propios del origen normal de la extensión, incluidos sus metadatos de cuota.
- `storage/default/moz-extension+++<uuid>^userContextId=4294967295`: principal reservado de `browser.storage.local`. Confirmado inspeccionando `modules/ExtensionStorageIDB.sys.mjs` del `assets/omni.ja` del AAR GeckoView **159.0.20261102144711** instalado, no solo documentación de otra versión. PhantomShield guarda aquí su configuración, memoria `aiMemory`, estadísticas y listas.
- `browser-extension-data/<id>/storage.js`: backend JSON heredado. Se ajusta la marca de migración IDB para no ignorar ese backend cuando no existe el origen reservado.
- XPI ubicados en `extensions/`, guardados con nombre SHA-256 para su reinstalación posterior.

Se conservan UUID y `.metadata-v2` junto a los archivos IDB y auxiliares. No se copia la base global de cuota ni otros sitios. Esta reconstrucción debe verificarse con el motor real: ante un error de cuota/IDB, restaura el respaldo en lugar de borrar archivos a ciegas. Extensiones desempaquetadas o instaladas fuera de `extensions/*.xpi` requieren reinstalación manual desde su fuente; sus datos seleccionados se conservan, pero no se fabrica un paquete.

## Seguridad y recuperación

AES-256-GCM, sal aleatoria de 16 bytes, nonce de 12 bytes y PBKDF2-HMAC-SHA256 de 210 000 iteraciones. La cabecera de versión se autentica como AAD. Primero se verifica GCM en un temporal privado; solo entonces se abre ZIP. Cada archivo figura con tamaño y SHA-256. Se rechazan rutas absolutas, escapes, duplicados, enlaces y archivos ajenos al esquema. Límites: 4 000 archivos, 64 MiB por archivo y 128 MiB en total. Una copia que excede esos límites falla íntegramente; no se omite una base de datos para hacerla caber.

Los temporales descifrados y el respaldo del destino se guardan en almacenamiento privado de la aplicación, excluido de Android Backup por el manifest. Se eliminan los temporales después de la operación y al volver a mantenimiento. Borrar archivos no garantiza borrado físico en flash.

Antes de reemplazar archivos se guardan sus originales y un diario sincronizado en `files/transfer-journal`. Cada reemplazo usa temporal sincronizado y rename atómico. El diario solo se marca confirmado al terminar. Un proceso interrumpido durante aplicar/restaurar vuelve al mantenimiento y restaura los originales antes de otra operación. También se conservan/restauran los sidecars nativos `.bak`/`.new`; no deben resucitar preferencias antiguas sobre los archivos importados. No se permite salir con una transacción incompleta.

Una importación confirmada conserva su respaldo hasta que se restaure o elimine expresamente. Restaurar también reemplaza cambios posteriores en los archivos afectados; no es una combinación de perfiles. No desinstala extensiones que hayas instalado después de importar ni revierte sus binarios: revísalas manualmente al restaurar. Una nueva exportación/importación después de aplicar o restaurar exige salir del mantenimiento y un proceso nuevo para descartar cachés nativas. Las operaciones se serializan por proceso incluso si Android recrea la pantalla. Las pruebas simulan interrupciones en fronteras de archivo, no fallos físicos del almacenamiento ni corte de energía durante operaciones del sistema de archivos. Sin espacio, permisos o soporte de rename atómico, mantén la aplicación en mantenimiento y restaura; no se promete recuperación frente a un dispositivo averiado.

## Prueba real pendiente

Con instalaciones de prueba firmadas apropiadamente y datos desechables:

- Crear grupos, pestañas normales/privadas/Burner, accesos y claves; cambiar configuración y memoria PhantomShield; instalar una extensión firmada que use storage.local e IndexedDB.
- Exportar de transición e importar en el paquete nuevo con datos ya existentes. Verificar URLs/grupos y ausencia de privadas, Burner y descargas; comparar claves mediante una operación de cada servicio sin imprimirlas.
- Reinstalar XPI y comprobar firma/permisos. Leer y escribir storage.local/IndexedDB y la memoria/configuración PhantomShield; cerrar y reabrir dos veces para detectar fallos de cuota, UUID o cachés.
- Matar el proceso durante descifrado, preparación, cada fase de aplicación y restauración; volver a abrir y comprobar que el perfil anterior continúa legible. Probar contraseña errónea, truncamiento, archivo manipulado, disco lleno y denegación del proveedor de documentos.
- Restaurar el respaldo después de una importación confirmada. Verificar las preferencias y claves originales del destino y que el origen sigue intacto.
