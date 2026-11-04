"""Serialize top-level operations on this explicit own local runtime directory."""
import fcntl
from management_db import D
handle=(D/'setup.lock').open('a')
(D/'setup.lock').chmod(0o600)
fcntl.flock(handle,fcntl.LOCK_EX)
