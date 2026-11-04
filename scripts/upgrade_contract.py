"""Only explicitly pinned additive B->C->D transitions authorize schema changes."""
import json
from management_db import R,sql
from persistence_contract import calculate

def plans():
    return [json.loads((R/name).read_text()) for name in ['db/upgrade-MA-B-to-MA-C.json','db/upgrade-MA-C-to-MA-D.json','db/upgrade-MA-D-to-MA-F.json']]
def recognized_contract(current):
    _,target=calculate()
    return current in [target,*[p['fromFingerprint'] for p in plans()]]

def apply():
    body,target=calculate();chain=plans()
    exists=sql("SELECT to_regclass('di_ops.contract_fingerprint') IS NOT NULL;")=='t'
    current=sql('SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;') if exists else ''
    known=[p['fromFingerprint'] for p in chain]
    if current and current not in [target,*known]:raise RuntimeError('Unknown predecessor: refusing additive upgrade before any DDL')
    if current==target:print('Current explicit contract retained');return
    selected=chain[known.index(current):] if current else chain
    artifacts=dict(line.split('=',1) for line in body.splitlines())
    for plan in selected:
        if any(artifacts.get(key)!=value for key,value in plan['unchangedArtifacts'].items()):raise RuntimeError('Predecessor model/mapping/migrations changed: explicit separate upgrade required')
    precondition=("LOCK TABLE di_ops.contract_fingerprint IN EXCLUSIVE MODE; DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM di_ops.contract_fingerprint WHERE singleton AND fingerprint='"+current+"') THEN RAISE EXCEPTION 'Concurrent contract change'; END IF; END $$;") if current else ''
    migrations='\n'.join((R/'db/migrations'/p['migration']).read_text() for p in selected)
    update=("UPDATE di_ops.contract_fingerprint SET fingerprint='"+target+"' WHERE singleton;") if current else ''
    sql('BEGIN;'+precondition+migrations+update+'COMMIT;')
    print('Applied explicit additive migration chain; existing model data retained')
if __name__=='__main__':apply()
