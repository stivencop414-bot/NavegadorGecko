# Firma persistente desde Nexo 0.14.1

Android conserva los datos al reemplazar una app si mantiene `applicationId`, un
`versionCode` superior y la misma identidad de firma. Nexo mantiene
`com.ejemplo.navegador` y sube versionCode de 28 a 29.

El propietario confirmó que usa un APK debug de Artifacts y no conserva su keystore.
Por su indicación se creó una nueva clave persistente RSA-4096 para iniciar la línea de
firma de 0.14.1. No se regenera en CI. Se preparó un respaldo privado fuera del checkout
con el keystore, el alias y las credenciales; el propietario debe descargarlo y conservarlo
en almacenamiento privado permanente. Ninguna clave privada ni contraseña se incluye en
el repositorio, APK, Releases o artefactos de CI.

## Configurar la firma en GitHub

En **Settings → Secrets and variables → Actions** del repositorio, crear o reemplazar:

- `ANDROID_KEYSTORE_BASE64`: contenido del archivo homónimo del respaldo (sin `.txt`).
- `ANDROID_KEYSTORE_PASSWORD`: contraseña del almacén incluida en el respaldo.
- `ANDROID_KEY_ALIAS`: `nexo`.
- `ANDROID_KEY_PASSWORD`: contraseña de la clave incluida en el respaldo.

La compilación no recibe estos secretos; solo el paso de firma los utiliza. Esta tarea
no pudo configurarlos: la integración devuelve `403 Resource not accessible by integration`
al consultar sus nombres y no se intentó eludir ese permiso. Los APK candidatos locales
sí se firmaron y verificaron con la nueva clave.

## Identidad fijada

`config/android-signing-certificate.sha256` contiene la huella pública de la nueva clave.
El workflow verifica cada firma con apksigner y exige ese certificado antes de subir o
publicar. Así una sustitución accidental del keystore bloquea la release. No cambies esa
huella para hacer pasar una clave distinta: restaura el respaldo original.

El verificador acepta los APK 0.14.1 firmados con la clave persistente y rechaza tanto un
APK debug con otra clave como la release antigua 0.13.6. La huella de 0.13.6 se verificó
contra su APK público y su SHA256SUMS: era
`8a2bf18b515d613aeb8b0da920a5f102d3a1741e9b3a736be2fc70e29aef3d0b`.
Ese certificado anterior no corresponde a la nueva clave ni permite reconstruirla.

## Primera transición desde Artifacts

Los APK debug del CI utilizan el `debug.keystore` del runner, que este workflow no conserva
entre ejecuciones. Como falta esa clave anterior, la nueva firma no permite instalar encima
de la app actual. Subir versionCode, mantener el paquete o firmar la extensión con Mozilla
no elimina esa comprobación de Android.

Esta primera transición necesita una migración con copia comprobada y reinstalación. No
se ejecutó ninguna desinstalación ni se garantiza conservación del historial antes de
preparar y verificar esa copia. No desinstales la app actual mientras falte ese paso.
Tras la transición, usa siempre APKs firmados con el respaldo de 0.14.1: las siguientes
versiones podrán instalarse encima conservando los datos, sujeto a comprobarlo en el dispositivo.

## Publicación y validación

Tras revisar y fusionar el PR: **Nexo Secure CI and Release → Run workflow**, desde `main`,
`mode=release`, tag vacío. Conservar los cuatro secrets y el respaldo privado para todas las
versiones. Si el tag ya existe, corregir y publicar una versión nueva; no borrar releases.

Verificar SHA256SUMS, certificado, instalación sobre una versión de la misma línea de firma,
conservación del historial/pestañas, PiP, descargas y traducción. El keystore no debe incluirse
nunca en los artefactos subidos ni en el código fuente. Los tests locales verificaron la firma
y los assets del APK; la migración de datos y la instalación en un teléfono siguen pendientes.
