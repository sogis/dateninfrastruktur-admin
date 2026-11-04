#!/usr/bin/env python3
"""Explicit wrapper around the Java importer for this own local Management runtime."""
import argparse,os,json,subprocess,sys
from management_db import R,D,state
parser=argparse.ArgumentParser();parser.add_argument('file');parser.add_argument('--apply',action='store_true');parser.add_argument('--select');parser.add_argument('--report',default=str(D.parent/'legacy-import-report.json'));parser.add_argument('--actor-issuer');parser.add_argument('--actor-subject');args=parser.parse_args()
if args.apply and (not args.select or not args.actor_issuer or not args.actor_subject):parser.error('Apply requires explicit --select, --actor-issuer and --actor-subject')
st=state();roles=json.loads((D/'roles.json').read_text());env=os.environ.copy();env.update(DI_MANAGEMENT_WRITER_JDBCURL='jdbc:postgresql://127.0.0.1:'+st['port']+'/dateninfrastruktur_management',DI_MANAGEMENT_WRITER_PASSWORD=roles['di_writer'])
if args.actor_issuer:env['DI_OPERATOR_ISSUER']=args.actor_issuer
if args.actor_subject:env['DI_OPERATOR_SUBJECT']=args.actor_subject
# Gradle --args uses shell-like argument splitting internally, so quote arguments for that parser (not a shell).
import shlex
values=[args.file,'--apply' if args.apply else '--dry-run','--report='+args.report]
if args.select:values+=['--select='+args.select]
p=subprocess.run([str(R/'gradlew'),'--no-daemon','legacyImport','--args='+shlex.join(values)],cwd=R,env=env,capture_output=True,text=True)
log=p.stdout+p.stderr
for value in roles.values():log=log.replace(value,'[REDACTED]')
print(log[-6000:]);sys.exit(p.returncode)
