"""Fetch only the exact pinned official INTERLIS distributions into this checkout's runtime."""
import hashlib,json,urllib.request,zipfile,io
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def ensure():
    versions = json.loads((ROOT / 'db/toolchain.json').read_text())
    for name, item in versions.items():
        target = ROOT / '.runtime/tools' / name
        jar = 'ili2c.jar' if name == 'ili2c' else f"{name}-{item['version']}.jar"
        if (target / jar).is_file():
            continue
        data=urllib.request.urlopen(item['url'],timeout=60).read()
        if hashlib.sha256(data).hexdigest()!=item['sha256']:raise RuntimeError('INTERLIS tool archive hash mismatch')
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if any(p.startswith('/') or '..' in p.split('/') for p in archive.namelist()):raise RuntimeError('Unsafe archive path')
            target.mkdir(parents=True, exist_ok=True)
            archive.extractall(target)
