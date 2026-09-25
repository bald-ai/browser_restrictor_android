#!/usr/bin/env python3
"""Explicit manual checks with disposable intermediate data and terminal results."""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = {'outputs': ['quality/metrics', 'quality/logs', 'quality/history', 'app/build/reports', 'app/build/test-results', 'app/build/jacoco', 'app/build/outputs/unit_test_code_coverage'], 'commands': {'metrics': [['python3', 'quality/run.py']], 'coverage': [['node', 'quality/javascript-coverage.mjs']], 'mutations': [['node', 'quality/mutate-javascript.mjs']], 'mutations:android': [['python3', 'quality/mutate-android.py']]}}


def run_session(commands, outputs, *, root=ROOT, coverage=False):
    # Refuse overlap: measurement and mutation tools require a stable checkout.
    lock = root / '.verification-session'
    lock.mkdir()
    links = []
    process = None
    previous = {}
    def forward(signum, _frame):
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signum)
        else:
            raise KeyboardInterrupt
    try:
        with tempfile.TemporaryDirectory(prefix=root.name + '-verification-') as folder:
            temporary = Path(folder)
            for index, relative in enumerate(outputs):
                target = root / relative
                if target.exists() or target.is_symlink():
                    raise RuntimeError(f'{relative} already exists; resolve leftover output or an active run before retrying.')
                target.parent.mkdir(parents=True, exist_ok=True)
                destination = temporary / str(index)
                destination.mkdir()
                target.symlink_to(destination, target_is_directory=True)
                links.append(target)
            env = {**os.environ, 'VERIFICATION_SESSION_ROOT': str(root),
                   'QUALITY_COVERAGE': '1' if coverage else '0',
                   'PYTHONDONTWRITEBYTECODE': '1'}
            test_jdk = env.get('QUALITY_JAVA_HOME') or env.get('ANDROID_TEST_JAVA_HOME')
            if not test_jdk:
                for prefix in ('/opt/homebrew', '/usr/local'):
                    candidate = Path(prefix) / 'opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'
                    if (candidate / 'bin/java').exists():
                        test_jdk = str(candidate)
                        break
            test_jdk = test_jdk or env.get('JAVA_HOME')
            if test_jdk:
                env['JAVA_HOME'] = test_jdk
            if env.get('JAVA_HOME'):
                env.setdefault('QUALITY_JAVA_HOME', env['JAVA_HOME'])
            for signum in (signal.SIGINT, signal.SIGTERM):
                previous[signum] = signal.signal(signum, forward)
            for command in commands:
                command = list(command)
                if command[0] == './gradlew' and env.get('JAVA_HOME'):
                    command.insert(1, '-Dorg.gradle.java.home=' + env['JAVA_HOME'])
                print('$ ' + ' '.join(command), flush=True)
                process = subprocess.Popen(command, cwd=root, env=env, start_new_session=True)
                code = process.wait()
                if code:
                    # Internal tools may capture diagnostic output for parsing.
                    for log in sorted(temporary.rglob('*.log')):
                        print(log.read_text(errors='replace')[-6000:], flush=True)
                    return code if code > 0 else 128 - code
            return 0
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait()
        for signum, handler in previous.items():
            signal.signal(signum, handler)
        for target in reversed(links):
            if target.is_symlink():
                target.unlink()
        # SwiftPM has no separate output switch for its coverage counters.
        if coverage:
            for directory in (root / '.build').glob('**/codecov'):
                if directory.is_dir() and not directory.is_symlink():
                    shutil.rmtree(directory)
        lock.rmdir()


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in CONFIG['commands']:
        print('Manual checks only. Usage: python3 scripts/verification-session.py [' + '|'.join(CONFIG['commands']) + '] [selection ...]')
        return 2
    mode = sys.argv[1]
    commands = [list(command) for command in CONFIG['commands'][mode]]
    if len(sys.argv) > 2:
        if not mode.startswith('mutations'):
            raise SystemExit('Selections are supported only for mutations.')
        commands[-1].extend(sys.argv[2:])
    return run_session(commands, CONFIG['outputs'], coverage=mode in ('metrics', 'coverage'))


if __name__ == '__main__':
    raise SystemExit(main())
