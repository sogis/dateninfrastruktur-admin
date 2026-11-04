"""Fetch only the exact pinned official INTERLIS distributions into this checkout's runtime."""
import hashlib,json,urllib.request,zipfile,io
from management_db import R

def ensure():
    for name,item in json.loads((R/'db/toolchain.json').read_text()).items():
        target=R/'.runtime/tools'/name
        if target.exists():continue
        data=urllib.request.urlopen(item['url'],timeout=60).read()
        if hashlib.sha256(data).hexdigest()!=item['sha256']:raise RuntimeError('INTERLIS tool archive hash mismatch')
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if any(p.startswith('/') or '..' in p.split('/') for p in archive.namelist()):raise RuntimeError('Unsafe archive path')
            target.mkdir(parents=True);archive.extractall(target)
