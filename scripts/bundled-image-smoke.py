#!/usr/bin/env python3
"""CI only: real bundled assets/API under a 512 MiB, 0.1 CPU container limit.

Uses local PostgreSQL and the bundled-ui profile, NOT Neon TLS or Render's edge.
No images published, cloud resources or persistent demo data created.
"""
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request


def main():
    name = 'operationsflow-bundled-ci'
    env = dict(os.environ)
    env.update(POSTGRES_USER='operationsflow', POSTGRES_DB='operationsflow',
               POSTGRES_PASSWORD=secrets.token_urlsafe(32))
    env.update(DB_URL=f'jdbc:postgresql://{name}-db:5432/operationsflow',
               DB_USERNAME=env['POSTGRES_USER'], DB_PASSWORD=env['POSTGRES_PASSWORD'],
               SPRING_PROFILES_ACTIVE='bundled-ui', SERVER_ADDRESS='0.0.0.0', SERVER_PORT='10000',
               SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',
               SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
               SERVER_TOMCAT_THREADS_MAX='20', SERVER_TOMCAT_THREADS_MIN_SPARE='2',
               BOOTSTRAP_ADMIN_EMAIL='bundle-review@example.invalid',
               BOOTSTRAP_ADMIN_PASSWORD=secrets.token_urlsafe(32))
    password = env['BOOTSTRAP_ADMIN_PASSWORD']

    def docker(*args):
        return subprocess.run(['docker', *args], env=env, check=True, capture_output=True, text=True)

    def client():
        return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    browser = client()

    def request(path, body=None, csrf=None):
        headers = {'Content-Type': 'application/json'} if body is not None else {}
        if csrf:
            headers[csrf['headerName']] = csrf['token']
        req = urllib.request.Request('http://127.0.0.1:18081' + path, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
        try:
            with browser.open(req, timeout=60) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers

    def start(bootstrap):
        variables = ['DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'SPRING_PROFILES_ACTIVE',
                     'SERVER_ADDRESS', 'SERVER_PORT', 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE',
                     'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE', 'SERVER_TOMCAT_THREADS_MAX',
                     'SERVER_TOMCAT_THREADS_MIN_SPARE']
        if bootstrap:
            variables += ['BOOTSTRAP_ADMIN_EMAIL', 'BOOTSTRAP_ADMIN_PASSWORD']
        args = [item for variable in variables for item in ('-e', variable)]
        began = time.monotonic()
        docker('run', '-d', '--name', name, '--network', name, '--memory=512m',
               '--memory-swap=512m', '--cpus=0.1', '-p', '127.0.0.1:18081:10000',
               *args, 'operationsflow-demo:ci')
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            if docker('inspect', '--format', '{{.State.Running}}', name).stdout.strip() != 'true':
                raise RuntimeError('Bundled container exited before readiness (check memory/startup)')
            try:
                status, body, _ = request('/actuator/health/readiness')
                if status == 200 and json.loads(body)['status'] == 'UP':
                    print(f'Bundled readiness in {time.monotonic() - began:.1f}s (CI, not Render)')
                    return
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(5)
        raise RuntimeError('Bundled image readiness timed out')

    try:
        docker('network', 'create', name)
        docker('run', '-d', '--name', name + '-db', '--network', name,
               '-e', 'POSTGRES_USER', '-e', 'POSTGRES_DB', '-e', 'POSTGRES_PASSWORD',
               'postgres:17.6-alpine')
        for _ in range(60):
            result = subprocess.run(['docker', 'exec', name + '-db', 'pg_isready',
                                     '-U', 'operationsflow', '-d', 'operationsflow'], capture_output=True)
            if result.returncode == 0:
                break
            time.sleep(2)
        else:
            raise RuntimeError('CI database unavailable')
        start(True)
        status, body, _ = request('/')
        assert status == 200 and b'id="root"' in body
        assets = re.findall(rb'(?:src|href)="(/assets/[^"<>]+)"', body)
        assert assets, 'Production Vite bundle references missing'
        for asset in assets:
            status, data, headers = request(asset.decode())
            assert status == 200 and data and 'text/html' not in headers.get('Content-Type', '')
        assert request('/orders')[0] == 200
        assert request('/api/v1/orders')[0] == 401
        status, data, _ = request('/api/does-not-exist')
        assert status == 401 and b'id="root"' not in data
        login = {'email': 'bundle-review@example.invalid', 'password': password}
        assert request('/api/v1/auth/login', login)[0] == 403
        token = json.loads(request('/api/v1/auth/csrf')[1])
        assert request('/api/v1/auth/login', login, token)[0] == 200
        assert request('/api/v1/products')[0] == 200
        docker('rm', '-f', name)
        # Normal restart never receives bootstrap credentials; identity persists but session does not.
        env.pop('BOOTSTRAP_ADMIN_PASSWORD')
        env.pop('BOOTSTRAP_ADMIN_EMAIL')
        start(False)
        assert request('/api/v1/products')[0] == 401
        token = json.loads(request('/api/v1/auth/csrf')[1])
        assert request('/api/v1/auth/login', login, token)[0] == 200
        print('PASS: compiled assets, SPA, API boundary, CSRF, login, database persistence and session loss')
    finally:
        subprocess.run(['docker', 'rm', '-f', name, name + '-db'], capture_output=True)
        subprocess.run(['docker', 'network', 'rm', name], capture_output=True)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit('Container command failed; sensitive command output suppressed.')
