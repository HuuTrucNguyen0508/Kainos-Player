#!/usr/bin/env python3
"""Inspect the actual APK DEX and signing identity without installing on a device."""
import argparse
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def classes(apk):
    result = set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            dex = archive.read(name)
            u32 = lambda offset: struct.unpack_from('<I', dex, offset)[0]
            strings_size, strings_offset = u32(56), u32(60)
            strings = []
            for index in range(strings_size):
                offset = u32(strings_offset + 4 * index)
                while dex[offset] & 128:
                    offset += 1
                offset += 1
                strings.append(dex[offset:dex.index(b'\0', offset)].decode('utf-8', errors='replace'))
            types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
            for index in range(u32(96)):
                result.add(types[u32(u32(100) + 32 * index)])
    return result


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('debug', type=Path)
    parser.add_argument('optimized', type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    args = parser.parse_args()
    report = {}
    identities = []
    for kind, apk in [('debug', args.debug), ('optimized', args.optimized)]:
        signing = run(str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(apk))
        cert = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', signing).group(1)
        manifest = run(str(args.build_tools / 'aapt'), 'dump', 'badging', str(apk))
        package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", manifest).groups()
        identities.append((cert, package))
        names = classes(apk)
        required = [
            'Lcom/universalmusic/player/platform/EndSafeNativeDecoder;',
            'Lxyz/gianlu/librespot/core/Session;',
            'Lxyz/gianlu/librespot/android/sink/AndroidSinkOutput;',
            'Lcom/google/protobuf/GeneratedMessageV3;',
            'Lcom/spotify/Authentication$LoginCredentials;',
            'Lorg/schabi/newpipe/extractor/NewPipe;',
            'Lorg/mozilla/javascript/Context;',
        ]
        missing = [name for name in required if name not in names]
        assert not missing, f'{kind}: missing actual DEX class definitions: {missing}'
        assert package[0] == 'com.universalmusic.player', package
        debuggable = 'application-debuggable' in manifest
        assert debuggable == (kind == 'debug'), f'{kind}: debuggable={debuggable}'
        with zipfile.ZipFile(apk) as archive:
            profiles = [name for name in archive.namelist() if name.startswith('assets/dexopt/')]
        report[kind] = dict(bytes=apk.stat().st_size, classes=len(names), certificate_sha256=cert,
                            application_id=package[0], version_code=int(package[1]),
                            debuggable=debuggable, packaged_profiles=profiles, required_classes_present=required)
    assert identities[0][0] == identities[1][0], 'APK signatures differ; in-place update would fail'
    assert int(identities[1][1][1]) >= int(identities[0][1][1]), 'Version downgrade'
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
