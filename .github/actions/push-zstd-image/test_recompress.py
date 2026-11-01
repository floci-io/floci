"""Test recompress.py against small OCI layouts written here.

Each layout holds real layer tars, so every check runs the zstd CLI the way the action does: a
layer must come out as zstd that decompresses back to its diff_id, and every manifest and index
above it must point at the new blobs.
"""

from pathlib import Path
import gzip
import hashlib
import io
import json
import subprocess
import sys
import tarfile
import tempfile
import unittest


RECOMPRESS = Path(__file__).resolve().parent / 'recompress.py'
INDEX = 'application/vnd.oci.image.index.v1+json'
MANIFEST = 'application/vnd.oci.image.manifest.v1+json'
CONFIG = 'application/vnd.oci.image.config.v1+json'
TAR = 'application/vnd.oci.image.layer.v1.tar'
GZIP = TAR + '+gzip'
ZSTD = TAR + '+zstd'


def layer_tar(name, content):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode='w', format=tarfile.PAX_FORMAT) as tar:
        info = tarfile.TarInfo(name)
        info.size = len(content)
        tar.addfile(info, io.BytesIO(content))
    return out.getvalue()


def sha256(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


class Layout:
    def __init__(self, root):
        self.root = root
        (root / 'blobs' / 'sha256').mkdir(parents=True)
        (root / 'oci-layout').write_text('{"imageLayoutVersion":"1.0.0"}')

    def blob(self, digest):
        return self.root / 'blobs' / 'sha256' / digest.removeprefix('sha256:')

    def put(self, data, media_type, **extra):
        self.blob(sha256(data)).write_bytes(data)
        return {'mediaType': media_type, 'digest': sha256(data), 'size': len(data), **extra}

    def put_json(self, document, media_type, **extra):
        return self.put(json.dumps(document).encode(), media_type, **extra)

    def image(self, layers, diff_ids=None, architecture='amd64'):
        """layers: (tar bytes, media type) pairs. Returns the manifest descriptor."""
        descriptors = [self.put(gzip.compress(tar, mtime=0) if media_type == GZIP else tar, media_type)
                       for tar, media_type in layers]
        config = {'architecture': architecture, 'os': 'linux',
                  'rootfs': {'type': 'layers', 'diff_ids': diff_ids or [sha256(tar) for tar, _ in layers]}}
        manifest = {'schemaVersion': 2, 'mediaType': MANIFEST, 'config': self.put_json(config, CONFIG),
                    'layers': descriptors}
        return self.put_json(manifest, MANIFEST, platform={'architecture': architecture, 'os': 'linux'})

    def index(self, manifests):
        return self.put_json({'schemaVersion': 2, 'mediaType': INDEX, 'manifests': manifests}, INDEX)

    def name(self, descriptor):
        (self.root / 'index.json').write_text(json.dumps(
            {'schemaVersion': 2, 'mediaType': INDEX, 'manifests': [descriptor]}))

    def read(self, digest):
        return json.loads(self.blob(digest).read_bytes())

    def recompress(self):
        return subprocess.run([sys.executable, str(RECOMPRESS), str(self.root), '3'], capture_output=True, text=True)


class RecompressTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.layout = Layout(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def run_ok(self):
        result = self.layout.recompress()
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def assert_zstd_layers(self, manifest_digest):
        manifest = self.layout.read(manifest_digest)
        diff_ids = self.layout.read(manifest['config']['digest'])['rootfs']['diff_ids']
        for layer, diff_id in zip(manifest['layers'], diff_ids):
            self.assertEqual(layer['mediaType'], ZSTD)
            data = self.layout.blob(layer['digest']).read_bytes()
            self.assertEqual(sha256(data), layer['digest'])
            self.assertEqual(len(data), layer['size'])
            tar = subprocess.run(['zstd', '-dc'], input=data, capture_output=True, check=True).stdout
            self.assertEqual(sha256(tar), diff_id)
        return manifest

    def test_single_platform_manifest(self):
        self.layout.name(self.layout.image([(layer_tar('base', b'base' * 1000), GZIP),
                                            (layer_tar('app', b'app' * 1000), TAR)]))

        digest = self.run_ok()

        named = json.loads((self.layout.root / 'index.json').read_text())['manifests'][0]
        self.assertEqual(named['digest'], digest)
        self.assertEqual(named['platform'], {'architecture': 'amd64', 'os': 'linux'})
        self.assertEqual(len(self.assert_zstd_layers(digest)['layers']), 2)

    def test_nested_index_with_a_layer_shared_between_platforms(self):
        shared = (layer_tar('entrypoint.sh', b'#!/bin/sh\n'), TAR)
        amd64 = self.layout.image([(layer_tar('base', b'amd64'), GZIP), shared], architecture='amd64')
        arm64 = self.layout.image([(layer_tar('base', b'arm64'), GZIP), shared], architecture='arm64')
        self.layout.name(self.layout.index([amd64, arm64]))

        digest = self.run_ok()

        manifests = self.layout.read(digest)['manifests']
        self.assertEqual([m['platform']['architecture'] for m in manifests], ['amd64', 'arm64'])
        layers = [self.assert_zstd_layers(m['digest'])['layers'] for m in manifests]
        self.assertEqual(layers[0][1], layers[1][1])
        self.assertNotEqual(layers[0][0]['digest'], layers[1][0]['digest'])

    def test_layer_that_does_not_match_its_diff_id_fails(self):
        self.layout.name(self.layout.image([(layer_tar('app', b'app'), TAR)], diff_ids=[sha256(b'other')]))

        result = self.layout.recompress()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('does not decompress to its diff_id', result.stderr)

    def test_second_run_changes_nothing(self):
        amd64 = self.layout.image([(layer_tar('base', b'amd64'), GZIP)], architecture='amd64')
        arm64 = self.layout.image([(layer_tar('base', b'arm64'), TAR)], architecture='arm64')
        self.layout.name(self.layout.index([amd64, arm64]))

        first = self.run_ok()
        index_json = (self.layout.root / 'index.json').read_bytes()
        second = self.run_ok()

        self.assertEqual(first, second)
        self.assertEqual((self.layout.root / 'index.json').read_bytes(), index_json)

    def test_docker_media_type_manifest_fails(self):
        self.layout.name(self.layout.put_json({'schemaVersion': 2}, 'application/vnd.docker.distribution.manifest.v2+json'))

        result = self.layout.recompress()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('oci-mediatypes=true', result.stderr)


if __name__ == '__main__':
    unittest.main()
