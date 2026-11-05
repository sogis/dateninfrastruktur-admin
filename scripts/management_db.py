#!/usr/bin/env python3
"""Explicit tool database target; all container lifecycle belongs to STACK Compose."""
import os,pathlib,subprocess
R=pathlib.Path(__file__).resolve().parents[1]
TARGET_MODE=os.environ.get('DI_DATABASE_TARGET')=='postgresql'
D=pathlib.Path(os.environ.get('DI_TOOL_RUNTIME','/runtime/tools') if TARGET_MODE else R/'.runtime/retired-management').resolve()
if TARGET_MODE:D.mkdir(parents=True,exist_ok=True);D.chmod(0o700)
from database_target import sql

def cmd(args,env=None,input=None):
    if pathlib.Path(args[0]).name=='docker':raise RuntimeError('Container control is retired here; use the STACK Compose lifecycle')
    result=subprocess.run(args,capture_output=True,text=True,env=env,input=input)
    if result.returncode:raise RuntimeError('Explicit tool operation failed; SQL/credentials withheld')
    return result.stdout.strip()
def _retired(*args,**kwargs):raise RuntimeError('Legacy container IDs/state.json are retired. Use STACK scripts with an explicit Compose environment; no data was changed.')
start=state=cleanup=ili_schema=mapping=_retired
if __name__=='__main__':raise SystemExit('Database tools require an explicit PostgreSQL target inside STACK Compose; no server is created here.')
