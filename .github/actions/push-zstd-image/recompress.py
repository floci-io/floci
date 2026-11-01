#!/usr/bin/env python3
"""Recompress the layers of the images in an OCI layout with zstd.

Usage: recompress.py <layout-dir> <level>

BuildKit compresses zstd at about level 11 at most, so the image is exported to an OCI layout and
its layers are recompressed here with the zstd CLI. Every manifest and index that points at a
rewritten blob is rewritten too, and the digest of the image that index.json names is printed for
the second build to push it from. Each layer's uncompressed content is checked against its
diff_id in the image config, so the image content cannot change on the way.
"""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

INDEX = 'application/vnd.oci.image.index.v1+json'
MANIFEST = 'application/vnd.oci.image.manifest.v1+json'
ZSTD = 'application/vnd.oci.image.layer.v1.tar+zstd'
OPENERS = {
    'application/vnd.oci.image.layer.v1.tar': open,
    'application/vnd.oci.image.layer.v1.tar+gzip': gzip.open,
    'application/vnd.docker.image.rootfs.diff.tar.gzip': gzip.open,
}
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
CHUNK = 1 << 20


def blob(layout, digest):
    if not DIGEST.fullmatch(digest):
        sys.exit(f'{digest} is not a sha256 digest')
    return layout / 'blobs' / 'sha256' / digest.removeprefix('sha256:')


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return 'sha256:' + h.hexdigest()


def write_json(layout, document):
    data = json.dumps(document, separators=(',', ':')).encode()
    digest = 'sha256:' + hashlib.sha256(data).hexdigest()
    blob(layout, digest).write_bytes(data)
    return digest, len(data)


def recompress_layer(layout, layer, diff_id, level):
    if layer['mediaType'] == ZSTD:
        return layer
    opener = OPENERS.get(layer['mediaType'])
    if opener is None:
        sys.exit(f"layer {layer['digest']} has media type {layer['mediaType']}, which this cannot recompress")

    uncompressed = hashlib.sha256()
    with tempfile.NamedTemporaryFile(dir=layout / 'blobs' / 'sha256', delete=False) as out:
        zstd = subprocess.Popen(['zstd', f'-{level}', '-T0', '-q', '-c'], stdin=subprocess.PIPE, stdout=out)
        with opener(blob(layout, layer['digest']), 'rb') as src:
            while chunk := src.read(CHUNK):
                uncompressed.update(chunk)
                zstd.stdin.write(chunk)
        zstd.stdin.close()
        if zstd.wait() != 0:
            sys.exit(f"zstd failed on layer {layer['digest']}")
    if 'sha256:' + uncompressed.hexdigest() != diff_id:
        sys.exit(f"layer {layer['digest']} does not decompress to its diff_id {diff_id}")

    digest = sha256_of(out.name)
    shutil.move(out.name, blob(layout, digest))
    size = blob(layout, digest).stat().st_size
    print(f"{layer['digest'][:19]}  {layer['size'] / 1e6:8.1f} MB  ->  zstd -{level}  {size / 1e6:8.1f} MB",
          file=sys.stderr)
    return {**layer, 'mediaType': ZSTD, 'digest': digest, 'size': size}


def rewrite(layout, descriptor, level):
    document = json.loads(blob(layout, descriptor['digest']).read_bytes())
    if descriptor['mediaType'] == INDEX:
        document['manifests'] = [rewrite(layout, d, level) for d in document['manifests']]
    elif descriptor['mediaType'] == MANIFEST:
        config = json.loads(blob(layout, document['config']['digest']).read_bytes())
        diff_ids = config['rootfs']['diff_ids']
        if len(diff_ids) != len(document['layers']):
            sys.exit(f"manifest {descriptor['digest']} has {len(document['layers'])} layers but {len(diff_ids)} diff_ids")
        document['layers'] = [recompress_layer(layout, layer, diff_id, level)
                              for layer, diff_id in zip(document['layers'], diff_ids)]
    else:
        sys.exit(f"{descriptor['digest']} has media type {descriptor['mediaType']}; export with oci-mediatypes=true")
    digest, size = write_json(layout, document)
    return {**descriptor, 'digest': digest, 'size': size}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('layout', type=Path)
    parser.add_argument('level', type=int)
    args = parser.parse_args()

    index_path = args.layout / 'index.json'
    index = json.loads(index_path.read_text())
    if len(index['manifests']) != 1:
        sys.exit(f"{index_path} names {len(index['manifests'])} images, expected one")
    index['manifests'] = [rewrite(args.layout, index['manifests'][0], args.level)]
    index_path.write_text(json.dumps(index, separators=(',', ':')))
    print(index['manifests'][0]['digest'])


if __name__ == '__main__':
    main()
