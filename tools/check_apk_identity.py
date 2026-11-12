"""Validate each package's manifest without requiring an Android device."""
import argparse
from pathlib import Path
import re
import subprocess


def check(build_tools, expected, apk):
    aapt = str(Path(build_tools) / 'aapt2')
    badging = subprocess.run([aapt, 'dump', 'badging', apk], check=True, capture_output=True, text=True).stdout
    assert re.search(r"package: name='" + re.escape(expected) + "'", badging), apk
    manifest = subprocess.run([aapt, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], check=True, capture_output=True, text=True).stdout
    assert f'"{expected}.files"' in manifest, 'FileProvider authority does not match applicationId'
    for name in ['NexoApplication', 'MainActivity', 'SettingsActivity', 'ExtensionsActivity', 'MigrationActivity', 'MediaPlaybackService', 'InternalOpenActivity']:
        assert f'"com.nexo.browser.{name}"' in manifest, f'Incorrect component: {name}'
    assert '"com.ejemplo.navegador.MainActivity"' not in manifest
    # Paso de datos entre paquetes: permiso de firma y proveedor activo solo en el paquete anterior.
    elements = re.split(r'\n(?=\s*E: )', manifest)
    permission = next((e for e in elements if e.lstrip().startswith('E: permission ')
                       and '"com.nexo.browser.permission.TRANSFER_PROFILE"' in e), None)
    assert permission, 'Missing transfer permission'
    level = re.search(r'protectionLevel\(0x01010009\)=(?:\(type 0x11\))?0x0*(\w+)', permission)
    assert level and int(level.group(1), 16) == 2, 'Transfer permission must be signature-only'
    provider = next((e for e in elements if e.lstrip().startswith('E: provider ')
                     and '"com.nexo.browser.LegacyProfileProvider"' in e), None)
    assert provider, 'Missing LegacyProfileProvider'
    assert f'"{expected}.profile"' in provider, 'Transfer provider authority does not match applicationId'
    assert '"com.nexo.browser.permission.TRANSFER_PROFILE"' in provider, 'Transfer provider must require the signature permission'
    enabled = re.search(r'android:enabled\(0x0101000e\)=(?:\(type 0x12\))?(\w+)', provider)
    assert enabled, 'Transfer provider enabled flag missing'
    active = enabled.group(1).lower() not in ('false', '0', '0x0', '0x00000000')
    assert active == (expected == 'com.ejemplo.navegador'), 'Transfer provider must be enabled only in the transition package'
    print(f'{apk}: package, components and FileProvider OK ({expected})')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('apks', nargs='+')
    args = parser.parse_args()
    for item in args.apks:
        check(args.build_tools, args.package, item)
