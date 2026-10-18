#!/usr/bin/env python3
"""Disposable CI-only stack: synthetic credentials, real sessions/CSRF, no secret output."""
import http.cookiejar
import json
import os
import secrets
import subprocess
import time
import urllib.error
import urllib.request


def main():
    env = dict(os.environ)
    env.update(POSTGRES_DB='operationsflow', POSTGRES_USER='operationsflow',
               POSTGRES_PASSWORD=secrets.token_urlsafe(32), SESSION_COOKIE_SECURE='false',
               APP_PORT='18080', BOOTSTRAP_ADMIN_EMAIL='container-check@example.invalid',
               BOOTSTRAP_ADMIN_PASSWORD=secrets.token_urlsafe(32))
    compose = ['docker', 'compose', '-p', 'operationsflow-ci', '-f', 'compose.full.yml']
    bootstrap = 'operationsflow-ci-bootstrap'

    def run(*args):
        return subprocess.run([*compose, *args], env=env, check=True, capture_output=True, text=True)

    try:
        run('up', '-d', '--wait', '--wait-timeout', '120', 'postgres')
        run('run', '-d', '--no-deps', '--name', bootstrap,
            '-e', 'BOOTSTRAP_ADMIN_EMAIL', '-e', 'BOOTSTRAP_ADMIN_PASSWORD', 'backend')
        for _ in range(120):
            result = subprocess.run(['docker', 'inspect', '--format',
                                     '{{.State.Status}} {{.State.Health.Status}}', bootstrap],
                                    check=True, capture_output=True, text=True).stdout.strip()
            if result == 'running healthy':
                break
            if not result.startswith('running '):
                raise RuntimeError('Bootstrap container exited before readiness')
            time.sleep(2)
        else:
            raise RuntimeError('Bootstrap readiness timed out')
        subprocess.run(['docker', 'rm', '-f', bootstrap], check=True, capture_output=True)
        del env['BOOTSTRAP_ADMIN_EMAIL']
        password = env.pop('BOOTSTRAP_ADMIN_PASSWORD')
        run('up', '-d', '--wait', '--wait-timeout', '180')
        cookies = http.cookiejar.CookieJar()
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        base = 'http://127.0.0.1:18080'

        def request(path, method='GET', body=None, csrf=None):
            headers = {}
            if body is not None:
                headers['Content-Type'] = 'application/json'
            if csrf:
                headers[csrf['headerName']] = csrf['token']
            req = urllib.request.Request(base + path, method=method, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
            try:
                with client.open(req, timeout=15) as response:
                    return response.status, response.read(), response.headers
            except urllib.error.HTTPError as error:
                return error.code, error.read(), error.headers

        assert request('/')[0] == 200
        assert request('/orders')[0] == 200  # SPA fallback
        assert request('/assets/missing.js')[0] == 404
        status, data, _ = request('/actuator/health/readiness')
        assert status == 200 and json.loads(data)['status'] == 'UP'
        assert request('/api/v1/system')[0] == 401
        login = {'email': 'container-check@example.invalid', 'password': password}
        assert request('/api/v1/auth/login', 'POST', login)[0] == 403
        status, data, _ = request('/api/v1/auth/csrf')
        assert status == 200
        assert request('/api/v1/auth/login', 'POST', login, json.loads(data))[0] == 200
        assert request('/api/v1/auth/session')[0] == 200
        assert request('/api/v1/system')[0] == 200
        assert request('/api/v1/products')[0] == 200
        session = next(cookie for cookie in cookies if cookie.name == 'JSESSIONID')
        assert session.has_nonstandard_attr('HttpOnly')
        assert session.get_nonstandard_attr('SameSite').lower() == 'strict'
        status, data, _ = request('/api/v1/auth/csrf')
        assert status == 200
        assert request('/api/v1/auth/logout', 'POST', {}, json.loads(data))[0] in (200, 204)
        assert request('/api/v1/system')[0] == 401
        # Recreate the backend and ensure nginx resolves its new address.
        run('up', '-d', '--force-recreate', '--wait', '--wait-timeout', '180', 'backend')
        for _ in range(15):
            if request('/actuator/health/readiness')[0] == 200:
                break
            time.sleep(2)
        else:
            raise RuntimeError('Proxy did not recover after backend recreation')
        print('PASS: stack readiness, SPA, API auth, CSRF, session cookies, database reads, logout, proxy recovery')
    finally:
        subprocess.run(['docker', 'rm', '-f', bootstrap], capture_output=True)
        # This project name is dedicated to disposable CI data, never developer volumes.
        run('down', '-v', '--remove-orphans')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError:
        # Avoid dumping Docker command output/environment containing credentials.
        raise SystemExit('Container command failed; no environment or sensitive output printed.')
