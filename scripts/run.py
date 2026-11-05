#!/usr/bin/env python3
"""Compatibility entry point: the only active runtime is STACK Compose."""
import pathlib,subprocess,sys
stack=pathlib.Path(__file__).resolve().parents[2]/'dateninfrastruktur-dev-stack'
raise SystemExit(subprocess.run([str(stack/"scripts/up.sh"),*sys.argv[1:]]).returncode)
