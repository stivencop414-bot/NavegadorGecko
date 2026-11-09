"""Comprueba que un APK optimizado con R8 puede arrancar.

Android crea las Activity, Service, ContentProvider, BroadcastReceiver y la
Application por reflexión, y lo mismo hacen ML Kit/Firebase con sus
ComponentRegistrar y AndroidX Startup con sus Initializer. Si R8 elimina el
constructor sin argumentos de alguna de esas clases, la app se cierra al
abrirse sin llegar a ejecutar código propio (fallo de Nexo 0.15.0 con AGP 9).

Uso: check_release_reflection.py --build-tools DIR app-release.apk
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

COMPONENT_TAGS = ("application", "activity", "service", "provider", "receiver")
REGISTRAR_PREFIX = "com.google.firebase.components:"


def manifest_classes(aapt2, apk):
    """Clases que el sistema o las librerías instancian a partir del manifiesto."""
    dump = subprocess.run(
        [aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
        capture_output=True, text=True, check=True,
    ).stdout
    package = re.search(r'package="([^"]+)"', dump)
    package = package.group(1) if package else ""

    classes = set()
    element = None
    for line in dump.splitlines():
        tag = re.match(r"\s*E: ([\w-]+)", line)
        if tag:
            element = tag.group(1)
            continue
        name = re.match(r'\s*A: http://schemas.android.com/apk/res/android:name\([^)]*\)="([^"]+)"', line)
        if not name or element is None:
            continue
        value = name.group(1)
        if element in COMPONENT_TAGS:
            classes.add(package + value if value.startswith(".") else value)
        elif element == "meta-data":
            if value.startswith(REGISTRAR_PREFIX):
                classes.add(value[len(REGISTRAR_PREFIX):])
            elif value.startswith("androidx.") and value.endswith("Initializer"):
                classes.add(value)
    return classes


def dex_constructors(dexdump, apk):
    """Mapa clase -> tiene constructor sin argumentos."""
    result = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            path = Path(tmp) / entry
            path.write_bytes(archive.read(entry))
            dump = subprocess.run([dexdump, str(path)], capture_output=True, text=True, check=True).stdout
            current = None
            section = None
            method = None
            for line in dump.splitlines():
                descriptor = re.match(r"\s*Class descriptor\s*:\s*'L([^;]+);'", line)
                if descriptor:
                    current = descriptor.group(1).replace("/", ".")
                    result.setdefault(current, False)
                    section = None
                    continue
                if "Direct methods" in line:
                    section = "direct"
                    continue
                if "Virtual methods" in line:
                    section = "virtual"
                    continue
                name = re.match(r"\s*name\s*:\s*'([^']+)'", line)
                if name:
                    method = name.group(1)
                    continue
                signature = re.match(r"\s*type\s*:\s*'([^']+)'", line)
                if signature and current and section == "direct" and method == "<init>":
                    if signature.group(1) == "()V":
                        result[current] = True
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-tools", required=True, help="Carpeta build-tools del Android SDK")
    parser.add_argument("apk", nargs="+")
    args = parser.parse_args()

    tools = Path(args.build_tools)
    failed = False
    for apk in args.apk:
        required = manifest_classes(str(tools / "aapt2"), apk)
        constructors = dex_constructors(str(tools / "dexdump"), apk)
        problems = []
        for name in sorted(required):
            if name not in constructors:
                # Las clases del framework de Android no están en el APK.
                if not name.startswith(("android.", "java.")):
                    problems.append(f"falta la clase {name}")
            elif not constructors[name]:
                problems.append(f"{name} no tiene constructor sin argumentos")
        if problems:
            failed = True
            print(f"::error::{apk}: el APK se cerraría al abrirse")
            for problem in problems:
                print("  - " + problem)
        else:
            print(f"{apk}: {len(required)} clases de arranque conservan su constructor")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
