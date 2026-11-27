#!/usr/bin/env python3
"""Opt-in two-JVM integration checks against a dedicated PostgreSQL verification DB.

Build the Boot jar first. Requires ARCHDECK_JDBC_URL (database name must end in
_verify), ARCHDECK_DB_USER/PASSWORD. Starts only its own loopback processes and
uses a synthetic HTTP Agent; no external collection or model calls are made.
"""
import argparse
from collections import Counter
import http.cookiejar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def eventually(action, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = action()
        if value:
            return value
        time.sleep(.25)
    raise AssertionError('Timed out waiting for integration state')


class Client:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, body=None, method='GET'):
        headers = {}
        if method != 'GET':
            token = self.request('/api/auth/csrf')[1]
            headers[token['headerName']] = token['token']
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            return error.code, None

    def login(self, username, password):
        token = self.request('/api/auth/csrf')[1]
        data = urllib.parse.urlencode({'username': username, 'password': password}).encode()
        request = urllib.request.Request(self.base + '/api/auth/login', data=data,
            headers={'Content-Type': 'application/x-www-form-urlencoded', token['headerName']: token['token']})
        with self.opener.open(request, timeout=20) as response:
            return json.load(response)


class Fixture:
    def __init__(self):
        self.lock = threading.Lock()
        self.calls = Counter()
        self.requests = {}
        self.gates = {}
        self.cancelled = set()
        self.token = secrets.token_urlsafe(40)

    def release(self, run):
        self.gates[run].set()

    def handler(self):
        state = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if self.headers.get('Authorization') != 'Bearer ' + state.token:
                    self.send_error(401)
                    return
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                run = self.path.split('/')[3]
                if self.path.endswith('/cancel'):
                    state.cancelled.add(run)
                    self.send_response(200)
                    self.end_headers()
                    return
                with state.lock:
                    state.calls[run] += 1
                    state.requests.setdefault(run, []).append(body)
                    gate = state.gates.setdefault(run, threading.Event())
                    attempt = state.calls[run]
                mode = body['policy']['domain_id']
                if mode in {'lease-smoke', 'cancel-smoke', 'recovery-smoke'} and attempt == 1:
                    gate.wait(90)
                if mode == 'transport-smoke' and attempt == 1:
                    self.close_connection = True
                    return
                quote = 'SYNTHETIC fixture: bounded retries are configurable.'
                draft = {'title': 'SYNTHETIC distributed verification', 'summary': quote,
                    'claims': [{'claim_id': 'c1', 'text': quote,
                        'evidence': [{'source_id': 's1', 'quote': quote}]}], 'risk_flags': []}
                result = {'runId': run, 'leaseFence': body['leaseFence'], 'status': 'needs_review',
                    'draft': draft, 'documents': [{'source_id': 's1',
                        'url': 'https://example.com/synthetic-distributed-fixture',
                        'title': 'SYNTHETIC fixture', 'excerpt': quote, 'reuse_allowed': True,
                        'published_at': body['asOf'], 'observed_at': body['asOf']}],
                    'issues': [], 'published': False, 'usage': {'modelCalls': 0}}
                encoded = json.dumps(result).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers()
                try:
                    self.wfile.write(encoded)
                except (BrokenPipeError, ConnectionResetError):
                    pass
        return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--ports', type=int, nargs=2, default=[4550, 4551])
    parser.add_argument('--agent-port', type=int, default=4560)
    args = parser.parse_args()
    env = dict(os.environ)
    jdbc = env.get('ARCHDECK_JDBC_URL', '')
    database = urllib.parse.urlsplit(jdbc.removeprefix('jdbc:')).path.strip('/')
    if not database.endswith('_verify'):
        parser.error('ARCHDECK_JDBC_URL must explicitly name a dedicated *_verify database')
    if not env.get('ARCHDECK_DB_PASSWORD'):
        parser.error('ARCHDECK_DB_PASSWORD is required')
    jar = args.jar or next(ROOT.glob('target/*.jar'), None)
    if not jar or not jar.is_file():
        parser.error('Build the Boot jar first or provide --jar')
    username = env.get('ARCHDECK_TEST_ADMIN_USERNAME', 'distributed-' + secrets.token_hex(5))
    initial = env.get('ARCHDECK_TEST_INITIAL_PASSWORD', secrets.token_urlsafe(30))
    password = env.get('ARCHDECK_TEST_ADMIN_PASSWORD', secrets.token_urlsafe(30))
    env.update(ARCHDECK_BOOTSTRAP_USERNAME=username, ARCHDECK_BOOTSTRAP_PASSWORD=initial,
        ARCHDECK_BIND_ADDRESS='127.0.0.1', ARCHDECK_SECURE_COOKIE='false',
        ARCHDECK_SESSION_COOKIE_NAME='ArchDeckDistributedSmoke', ARCHDECK_RUN_LEASE_SECONDS='6')
    fixture = Fixture()
    env['ARCHDECK_AGENT_SERVICE_TOKEN'] = fixture.token
    env['ARCHDECK_AGENT_URL'] = f'http://127.0.0.1:{args.agent_port}'
    server = ThreadingHTTPServer(('127.0.0.1', args.agent_port), fixture.handler())
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    processes, logs = [], []

    def launch(port):
        (ROOT / 'output').mkdir(exist_ok=True)
        log = tempfile.NamedTemporaryFile(prefix=f'distributed-{port}-', suffix='.log', dir=ROOT / 'output', delete=False)
        logs.append(log)
        process = subprocess.Popen([env.get('ARCHDECK_JAVA_BIN', 'java'), '-jar', str(jar)],
            cwd=ROOT, env={**env, 'SERVER_PORT': str(port)}, stdout=log, stderr=log)
        processes.append(process)
        client = Client(f'http://127.0.0.1:{port}')
        def ready():
            if process.poll() is not None:
                raise AssertionError(f'Verification backend exited; private log: {log.name}')
            try:
                return client.request('/api/health')[0] == 200
            except (urllib.error.URLError, TimeoutError):
                return False
        eventually(ready, 70)
        return client

    try:
        first = launch(args.ports[0])
        try:
            me = first.login(username, initial)
        except urllib.error.HTTPError:
            me = first.login(username, password)
        if me['mustChangePassword']:
            assert first.request('/api/auth/password', {'currentPassword': initial, 'newPassword': password}, 'POST')[0] in {200, 204}
            first.login(username, password)
        second = launch(args.ports[1])
        second.login(username, password)

        def enqueue(mode, client=first):
            status, policy = client.request('/api/research/policies', {'name': 'SYNTHETIC ' + mode,
                'domainId': mode, 'lookbackDays': 14, 'maxDocuments': 2, 'maxInputCharacters': 4000,
                'sources': [{'host': 'example.com', 'publisher_group': 'synthetic',
                    'feed_url': 'https://example.com/synthetic.rss', 'reuse_allowed': True}]}, 'POST')
            assert status in {200, 201}, ('policy', status)
            status, run = client.request('/api/research/runs', {'policyId': policy['id']}, 'POST')
            assert status in {200, 201, 202}, ('enqueue', status)
            return run['id']

        def row(run):
            return next(r for r in first.request('/api/research/runs')[1] if r['id'] == run)

        def candidates(run):
            return [c for c in first.request('/api/research/candidates')[1] if c['runId'] == run]

        one, two = enqueue('lease-smoke'), enqueue('lease-smoke', second)
        eventually(lambda: fixture.calls[one] and fixture.calls[two])
        before = [row(one), row(two)]
        assert len({r['leaseOwner'] for r in before}) == 2
        time.sleep(8)  # beyond a six-second lease: actual periodic renewal must keep ownership
        after = [row(one), row(two)]
        assert all(r['status'] == 'running' and r['attempt'] == 1 for r in after)
        assert all(a['heartbeatAt'] != b['heartbeatAt'] for a, b in zip(after, before))
        assert fixture.calls[one] == fixture.calls[two] == 1
        fixture.release(one)
        fixture.release(two)
        eventually(lambda: row(one)['status'] == row(two)['status'] == 'needs_review')
        assert len(candidates(one)) == len(candidates(two)) == 1
        print('PASS two JVMs claim independently, renew leases, accept one candidate each')

        retry = enqueue('transport-smoke')
        eventually(lambda: row(retry)['status'] == 'needs_review')
        assert fixture.calls[retry] == 2 and len(candidates(retry)) == 1
        requests = fixture.requests[retry]
        assert requests[1]['leaseFence'] > requests[0]['leaseFence']
        assert requests[0]['asOf'] == requests[1]['asOf']
        assert {k: v for k, v in requests[0].items() if k != 'leaseFence'} == {k: v for k, v in requests[1].items() if k != 'leaseFence'}
        print('PASS lost HTTP response replays frozen command with higher fence and one candidate')

        cancelled = enqueue('cancel-smoke')
        eventually(lambda: fixture.calls[cancelled])
        assert second.request(f'/api/research/runs/{cancelled}/cancel', {}, 'POST')[0] in {200, 204}
        eventually(lambda: cancelled in fixture.cancelled)
        fixture.release(cancelled)
        time.sleep(2)
        assert row(cancelled)['status'] == 'cancelled' and not candidates(cancelled)
        print('PASS cancellation crosses JVMs and late success creates no candidate')

        recovered = enqueue('recovery-smoke')
        eventually(lambda: fixture.calls[recovered])
        original = row(recovered)
        for process in processes:
            process.kill()
            process.wait(timeout=10)
        first = launch(args.ports[0])
        first.login(username, password)
        second = launch(args.ports[1])
        second.login(username, password)
        eventually(lambda: row(recovered)['status'] == 'needs_review')
        assert row(recovered)['leaseFence'] > original['leaseFence']
        assert row(recovered)['attempt'] > original['attempt']
        assert len(candidates(recovered)) == 1
        fixture.release(recovered)
        print('PASS hard-killed JVMs recover expired work after restart without duplicate candidates')
        print('All four distributed HTTP scenarios passed; external model calls: 0')
    finally:
        for gate in fixture.gates.values():
            gate.set()
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        server.shutdown()
        server.server_close()
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
