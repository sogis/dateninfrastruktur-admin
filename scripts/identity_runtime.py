#!/usr/bin/env python3
"""Compatibility entry point: the only active runtime is STACK Compose."""
import pathlib,subprocess,sys
stack=pathlib.Path(__file__).resolve().parents[2]/'dateninfrastruktur-dev-stack'
action=sys.argv[1] if len(sys.argv)>1 else "status"
actions={"start":"up","up":"up","stop":"stop","restart":"restart","status":"status","setup":"setup"}
if action not in actions:raise SystemExit("Legacy identity/container controls are retired; use STACK Compose operators and tests.")
raise SystemExit(subprocess.run([str(stack/"scripts"/(actions[action]+".sh")),*sys.argv[2:]]).returncode)
