#!/usr/bin/env python3
"""Disposable, loopback-only E2E stack. Never accepts a remote target or credentials."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]

def main():
    env = dict(os.environ)
    for key in list(env):
        if key.startswith(('BOOTSTRAP_', 'E2E_', 'SPRING_PROFILES_')):
            del env[key]
    env.update(POSTGRES_DB='operationsflow', POSTGRES_USER='operationsflow',
               POSTGRES_PASSWORD=secrets.token_urlsafe(32), SESSION_COOKIE_SECURE='false',
               APP_PORT='18081', BOOTSTRAP_ADMIN_EMAIL='browser-admin@example.invalid',
               BOOTSTRAP_ADMIN_PASSWORD=secrets.token_urlsafe(32))
    project = 'operationsflow-e2e-' + secrets.token_hex(5)
    compose = ['docker', 'compose', '-p', project, '-f', 'compose.full.yml']
    bootstrap = project + '-bootstrap'
    stage = 'database readiness'
    def run(*args):
        return subprocess.run([*compose, *args], cwd=ROOT, env=env, check=True,
                              capture_output=True, text=True)
    try:
        run('up', '-d', '--wait', '--wait-timeout', '120', 'postgres')
        stage = 'administrator bootstrap'
        run('run', '-d', '--no-deps', '--name', bootstrap,
            '-e', 'BOOTSTRAP_ADMIN_EMAIL', '-e', 'BOOTSTRAP_ADMIN_PASSWORD', 'backend')
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            result = subprocess.run(['docker', 'inspect', '--format',
                '{{.State.Status}} {{.State.Health.Status}}', bootstrap],
                check=True, capture_output=True, text=True).stdout.strip()
            if result == 'running healthy':
                break
            if not result.startswith('running '):
                raise RuntimeError()
            time.sleep(1)  # Readiness polling, never a browser-test sleep.
        else:
            raise RuntimeError()
        subprocess.run(['docker', 'rm', '-f', bootstrap], check=True, capture_output=True)
        password = env.pop('BOOTSTRAP_ADMIN_PASSWORD')
        env.pop('BOOTSTRAP_ADMIN_EMAIL')
        stage = 'application readiness'
        run('up', '-d', '--wait', '--wait-timeout', '180')
        stage = 'browser tests'
        env.update(E2E_ADMIN_PASSWORD=password, E2E_ISOLATED_STACK='1')
        result = subprocess.run(['npm', 'test'], cwd=ROOT / 'e2e', env=env)
        if result.returncode:
            raise RuntimeError()
        print('PASS: isolated real-application browser suite')
    except Exception:
        print('FAIL: E2E stage: ' + stage + '. Sensitive process output withheld.', file=sys.stderr)
        return 1
    finally:
        subprocess.run(['docker', 'rm', '-f', bootstrap], capture_output=True)
        # Only this invocation's random project; never shared/developer/production volumes.
        result = subprocess.run([*compose, 'down', '-v', '--remove-orphans'], cwd=ROOT,
                                env=env, capture_output=True)
        if result.returncode:
            print('FAIL: isolated stack cleanup; remove the E2E project manually.', file=sys.stderr)
    return 0

if __name__ == '__main__':
    sys.exit(main())
