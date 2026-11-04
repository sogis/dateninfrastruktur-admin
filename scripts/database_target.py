"""Explicit PostgreSQL tool target. No Docker access or implicit server creation."""
import os,re,subprocess
def connection():
    if os.environ.get('DI_DATABASE_TARGET')!='postgresql':raise RuntimeError('Explicit PostgreSQL target required')
    values={key:os.environ.get(key,'') for key in ['PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD']}
    if not values['PGHOST'] or not re.fullmatch(r'[A-Za-z0-9_.-]+',values['PGHOST']) or not values['PGPORT'].isdigit() or values['PGDATABASE']!='dateninfrastruktur_management' or not values['PGUSER'] or not values['PGPASSWORD']:raise RuntimeError('Incomplete or wrong explicit management database target')
    return values
def sql(query):
    env=os.environ.copy();env.update(connection())
    p=subprocess.run(['psql','-X','-q','-At','-v','ON_ERROR_STOP=1'],input=query,capture_output=True,text=True,env=env)
    if p.returncode:raise RuntimeError('Explicit management PostgreSQL operation failed (SQL/credentials withheld)')
    return p.stdout.strip()
