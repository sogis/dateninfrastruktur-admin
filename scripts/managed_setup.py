#!/usr/bin/env python3
"""Explicit first setup; an existing private current contract is verified without DDL."""
import subprocess,sys,time
from management_db import R,D,state,sql,cmd
if (D/'connection.json').exists() and state().get('private'):
    from persistence_contract import calculate
    st=state();cmd(['docker','start',st['container']])
    for _ in range(120):
        if subprocess.run(['docker','exec',st['container'],'pg_isready','-U','postgres'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
        time.sleep(.5)
    else:raise RuntimeError('Own database not ready; retained unchanged')
    _,expected=calculate()
    if sql('SELECT di_ops.runtime_contract();')!='t' or sql('SELECT di_ops.contract_fingerprint();')!=expected:raise RuntimeError('Existing private contract differs. Retained without DDL; explicit reviewed schema upgrade required.')
    subprocess.run([sys.executable,str(R/'scripts/verify_physical_mapping.py')],check=True)
    print('Existing private management schema verified. UUIDs, grants, audit, data and credentials retained; no DDL or seed.')
else:
    subprocess.run([sys.executable,str(R/'scripts/setup.py')],check=True)
