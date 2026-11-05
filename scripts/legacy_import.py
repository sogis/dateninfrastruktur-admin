#!/usr/bin/env python3
"""Explicit legacy importer delegates to the one Compose operator context."""
import pathlib,subprocess,sys
stack=pathlib.Path(__file__).resolve().parents[2]/'dateninfrastruktur-dev-stack'
raise SystemExit(subprocess.run([sys.executable,str(stack/'scripts/compose_operator.py'),'legacy-import',*sys.argv[1:]]).returncode)
