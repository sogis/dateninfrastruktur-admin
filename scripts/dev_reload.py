"""Run bootRun and publish only successfully compiled Java changes."""
import hashlib
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time

STATE = Path('/dev-state')
CHECKOUT = Path('/sources/admin')
PROJECT = STATE / 'project'
BUILD = STATE / 'build'
STAGING = STATE / 'staging'
CLASSES = BUILD / 'classes/java/main'
TRIGGER = BUILD / 'resources/main/.admin-dev-restart'
SOURCE = CHECKOUT / 'src/main/java'


def fingerprint():
    return {str(path): hashlib.sha256(path.read_bytes()).digest()
            for path in SOURCE.rglob('*.java') if path.is_file()}


def prepare_project():
    # Gradle requires a writable project directory even with external output
    # and cache locations. Only the directory is writable, not its inputs.
    PROJECT.mkdir(exist_ok=True)
    for name in ('build.gradle', 'settings.gradle', 'gradlew', 'gradle', 'src', 'scripts', 'db', 'models'):
        entry = PROJECT / name
        source = CHECKOUT / name
        if not entry.is_symlink():
            entry.symlink_to(source)


def gradle(build, task):
    return ['./gradlew', '--console=plain', '--max-workers=4',
            '--project-cache-dir', str(build / 'project-cache'),
            f'-PadminDevBuild={build}', task]


def publish_classes():
    pending = CLASSES.with_name('main.pending')
    previous = CLASSES.with_name('main.previous')
    shutil.rmtree(pending, ignore_errors=True)
    shutil.rmtree(previous, ignore_errors=True)
    shutil.copytree(STAGING / 'classes/java/main', pending)
    CLASSES.rename(previous)
    pending.rename(CLASSES)
    TRIGGER.touch()
    shutil.rmtree(previous)


def listening():
    try:
        with socket.create_connection(('127.0.0.1', 8080), timeout=1):
            return True
    except OSError:
        return False


def stop(signum, frame):
    raise SystemExit(128 + signum)


def main():
    signal.signal(signal.SIGTERM, stop)
    prepare_project()
    baseline = fingerprint()
    # Gradle tracks this output directory. Force the initial compile after any
    # preceding session, whose published classes came from the staging build.
    shutil.rmtree(CLASSES, ignore_errors=True)
    subprocess.run(gradle(BUILD, 'classes'), cwd=PROJECT, check=True)
    TRIGGER.touch()
    app = subprocess.Popen(gradle(BUILD, 'bootRun'), cwd=PROJECT)
    try:
        while app.poll() is None and not listening():
            time.sleep(1)
        while app.poll() is None:
            time.sleep(1)
            try:
                current = fingerprint()
            except FileNotFoundError:
                continue  # Editor replaced a file during this scan.
            if current == baseline:
                continue
            baseline = current
            if subprocess.run(gradle(STAGING, 'compileJava'), cwd=PROJECT).returncode == 0:
                # If another save happened during the compile, build it next
                # instead of publishing a potentially mixed set of inputs.
                try:
                    unchanged = fingerprint() == current
                except FileNotFoundError:
                    unchanged = False
                if unchanged:
                    publish_classes()
            else:
                print('Admin Java compilation failed. Save a correction to retry.')
        return app.returncode
    finally:
        if app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=10)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait()


if __name__ == '__main__':
    raise SystemExit(main())
