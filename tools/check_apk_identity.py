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
    print(f'{apk}: package, components and FileProvider OK ({expected})')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('apks', nargs='+')
    args = parser.parse_args()
    for item in args.apks:
        check(args.build_tools, args.package, item)
