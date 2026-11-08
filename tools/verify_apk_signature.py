"""Verify an APK and pin its signing identity before publishing; contains no private keys."""
import argparse
from pathlib import Path
import re
import subprocess
import sys


def verify(apksigner, apk, certificate_file):
    expected = Path(certificate_file).read_text(encoding="ascii").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("La huella de firma esperada debe ser un SHA-256 de certificado válido.")
    result = subprocess.run(
        [apksigner, "verify", "--verbose", "--print-certs", apk],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        raise ValueError("Firma APK inválida: " + result.stderr.strip())
    certificates = re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        result.stdout, re.MULTILINE,
    )
    if [value.lower() for value in certificates] != [expected]:
        raise ValueError(
            "El APK no está firmado con la identidad persistente configurada para Nexo desde v0.14.1. "
            "Se bloqueó la publicación para no romper las actualizaciones. "
            "Usa el keystore persistente respaldado para v0.14.1 en los secrets de firma."
        )
    print("Firma APK verificada; certificado SHA-256: " + expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--certificate", required=True)
    parser.add_argument("apk")
    args = parser.parse_args()
    try:
        verify(args.apksigner, args.apk, args.certificate)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
