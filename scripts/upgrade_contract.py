"""The sole additive MA-B -> MA-C transition. Unknown fingerprints never authorize DDL."""
import json
from management_db import R,sql
from persistence_contract import calculate

def recognized_contract(current):
    _,target=calculate()
    plan=json.loads((R/'db/upgrade-MA-B-to-MA-C.json').read_text())
    return current in (target,plan['fromFingerprint'])

def apply():
    body,target=calculate()
    plan=json.loads((R/'db/upgrade-MA-B-to-MA-C.json').read_text())
    exists=sql("SELECT to_regclass('di_ops.contract_fingerprint') IS NOT NULL;")=='t'
    current=sql('SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;') if exists else ''
    if current and current not in (target,plan['fromFingerprint']):
        raise RuntimeError('Unknown predecessor: refusing additive upgrade before any DDL')
    if current==target:
        print('Current MA-C contract retained');return
    # Revalidate under a lock and commit migration + fingerprint atomically.
    precondition=("LOCK TABLE di_ops.contract_fingerprint IN EXCLUSIVE MODE; DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM di_ops.contract_fingerprint WHERE singleton AND fingerprint='"+plan['fromFingerprint']+"') THEN RAISE EXCEPTION 'Concurrent contract change'; END IF; END $$;") if current else ''
    artifacts=dict(line.split('=',1) for line in body.splitlines())
    if any(artifacts.get(key)!=value for key,value in plan['unchangedArtifacts'].items()):
        raise RuntimeError('Predecessor model/mapping/migrations changed: explicit separate upgrade required')
    migration=(R/'db/migrations'/plan['migration']).read_text()
    update=("UPDATE di_ops.contract_fingerprint SET fingerprint='"+target+"' WHERE singleton;") if current else ''
    sql('BEGIN;'+precondition+migration+update+'COMMIT;')
    print('Applied explicit additive MA-C command-receipt upgrade; existing model data retained')
if __name__=='__main__':apply()
