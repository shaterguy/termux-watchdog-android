#!/data/data/com.termux/files/usr/bin/python3
"""Watchdog power-mode controller and durable command-resume fence."""
import fcntl
import hashlib
import json
from datetime import datetime, timezone
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.request import urlopen

HOME = Path('/data/data/com.termux/files/home')
PREFIX = Path('/data/data/com.termux/files/usr')
BRIDGE = HOME / 'watchdog-native-access-dev9-xivbdew7/bridge.dex'
BRIDGE_PIN = '5f3d130e78c925fdcec6c05352e94654223adc0bee70c5b5010c1c469a75ed37'
SERVICES = (
    'dooray-dbins-mcp', 'jina-web-inspector', 'korean-real-estate-mcp',
    'korean-stock-mcp', 'mcp-inventory', 'termux-dashboard',
    'youtube-transcript-mcp', 'selfrun-server', 'desktop-commander',
    'drive-worker', 'termux-google-auth',
)
MODE_SCHEMA = 'termux-watchdog-mode-v2'
UUID_RE = re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\Z')
LAST = SERVICES[-1]


class Controller:
    def __init__(self, home=HOME, prefix=PREFIX, execute=None, wait=time.sleep, clock=time.monotonic, idle=None):
        self.home, self.prefix = Path(home), Path(prefix)
        self.root = self.home / '.termux-watchdog'
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.file = self.root / 'power-mode-v2.json'
        self.lockfile = self.root / 'power-mode-v2.lock'
        self.worklockfile = self.root / 'power-mode-work-v2.lock'
        self.execute = execute or self.system
        self.wait, self.clock = wait, clock
        self.idle = idle or self.service_idle

    @staticmethod
    def system(args):
        return subprocess.run(args, capture_output=True, text=True, timeout=6, check=False)

    def lock(self, name=None):
        handle = open(name or self.lockfile, 'a+b')
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        return handle

    def fresh(self):
        return dict(schema=MODE_SCHEMA, command_id='', command_sequence=0, state='normal',
                    requested_mode='normal', operation_id='', revision=0, auth_owner='android',
                    auth_state='unknown', auth_broker_running=False,
                    recovery_suppressed=False, paused_services=0, pending_services=0,
                    error_code='', shared_wakelock_state='ownership_unknown_no_release',
                    prior=[], target=[])

    def load(self):
        if not self.file.exists():
            return self.fresh()
        raw = json.loads(self.file.read_text())
        if raw.get('schema') != MODE_SCHEMA:
            raise ValueError('BAD_SCHEMA')
        return raw

    def save(self, record):
        tmp = self.root / ('power-mode-v2.json.' + uuid.uuid4().hex)
        raw = json.dumps(record, separators=(',', ':')) + '\n'
        with open(tmp, 'x') as f:
            os.chmod(tmp, 0o600)
            f.write(raw)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.file)


    def persist_resume_fence(self, record):
        # The fence must be durable BEFORE any service can be restarted.
        server_name = (self.home / 'SERVER_NAME').read_text().strip()
        if not server_name or len(server_name) > 128:
            raise RuntimeError('RESUME_SERVER_NAME_UNAVAILABLE')
        filename = self.root / 'command-resume-fence-v1.json'
        resumed_at_ms = time.time_ns() // 1_000_000
        if filename.exists():
            prior = json.loads(filename.read_text())
            if prior.get('schema') != 'drive-worker-resume-fence-v1' or prior.get('server_name') != server_name:
                raise RuntimeError('PREVIOUS_RESUME_FENCE_INVALID')
            prev_ms = int(datetime.fromisoformat(prior['resumed_at'].replace('Z', '+00:00')).timestamp() * 1000)
            if resumed_at_ms <= prev_ms:
                raise RuntimeError('RESUME_CLOCK_ROLLBACK')
        value = {
            'schema': 'drive-worker-resume-fence-v1',
            'server_name': server_name,
            'resumed_at': datetime.fromtimestamp(resumed_at_ms / 1000, timezone.utc).isoformat(
                timespec='milliseconds').replace('+00:00', 'Z'),
            'command_sequence': record['command_sequence'],
            'operation_id': record['operation_id'],
        }
        staged = self.root / ('command-resume-fence-v1.json.' + uuid.uuid4().hex)
        with open(staged, 'x') as stream:
            os.chmod(staged, 0o600)
            stream.write(json.dumps(value, separators=(',', ':')) + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(staged, filename)
        fd = os.open(self.root, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        if json.loads(filename.read_text()) != value:
            raise RuntimeError('RESUME_FENCE_READBACK_MISMATCH')
        return value

    def running(self, svc):
        command = [str(self.prefix / 'bin/sv'), 'status', str(self.prefix / 'var/service' / svc)]
        ret = self.execute(command)
        if ret.returncode != 0 or not ret.stdout.startswith(('run:','down:')):
            raise RuntimeError('SERVICE_STATUS_UNAVAILABLE')
        return ret.stdout.startswith('run:')

    def change(self, svc, desired):
        result = self.execute([str(self.prefix / 'bin/sv'), 'up' if desired else 'down',
                               str(self.prefix / 'var/service' / svc)])
        if result.returncode != 0:
            raise RuntimeError('SERVICE_TRANSITION_FAILED')
        deadline = self.clock() + 50
        while self.clock() < deadline:
            if self.running(svc) == desired:
                return
            self.wait(1)
        raise RuntimeError('SERVICE_TRANSITION_TIMEOUT')

    def service_idle(self, svc):
        url = {'drive-worker':'http://127.0.0.1:17841/health',
               'selfrun-server':'http://127.0.0.1:17831/health'}.get(svc)
        if not url:
            return True
        try:
            with urlopen(url, timeout=3) as result:
                state = json.load(result)
            if state.get('ok') is not True:
                return False
            if svc == 'drive-worker':
                drain = state.get('drain') or {}
                # The worker keeps historical publication failures even after another
                # cluster member has published a terminal result. They are diagnostic
                # history, not evidence of a currently executing job. Never ignore
                # unsettled jobs, in-flight commands, children or active jobs.
                return not state.get('active_jobs') and not state.get('active_job') and \
                    not drain.get('in_flight_commands') and not drain.get('active_children') and \
                    not drain.get('unsettled_job_ids')
            watcher = state.get('drive_watcher') or {}
            return state.get('status') == 'IDLE' and not (state.get('browser_runtime') or {}).get('pending') and \
                not watcher.get('pendingFiles') and not watcher.get('pendingReads') and \
                not watcher.get('scanning')
        except Exception:
            return False

    def restore_stuck_drive_worker(self):
        """On mode disable, restart only a worker stuck in prior shutdown.

        A healthy worker is left untouched. Never interrupt active job PIDs.
        This also heals old, pre-upgrade BLOCKED worker processes.
        """
        url = 'http://127.0.0.1:17841/health'
        def probe():
            with urlopen(url, timeout=3) as response:
                return json.load(response)
        deadline = self.clock() + 20
        previous = None
        while self.clock() < deadline:
            try:
                previous = probe()
                break
            except Exception:
                self.wait(1)
        if not isinstance(previous, dict) or previous.get('ok') is not True:
            raise RuntimeError('WORKER_HEALTH_UNAVAILABLE')
        if not (previous.get('drain') or {}).get('requested'):
            return
        if previous.get('active_jobs') or previous.get('active_job'):
            raise RuntimeError('WORKER_ACTIVE_JOBS')
        started_at = previous.get('started_at')
        command = [str(self.prefix / 'bin/sv'), 'kill',
                   str(self.prefix / 'var/service/drive-worker')]
        result = self.execute(command)
        if result.returncode != 0:
            raise RuntimeError('WORKER_RESTART_FAILED')
        deadline = self.clock() + 45
        while self.clock() < deadline:
            try:
                updated = probe()
                if updated.get('ok') is True and \
                        not (updated.get('drain') or {}).get('requested') and \
                        updated.get('started_at') != started_at:
                    return
            except Exception:
                pass
            self.wait(1)
        raise RuntimeError('WORKER_RESTART_UNVERIFIED')

    def auth_state(self):
        if not self.running(LAST):
            return 'not_ready'
        # No tokens or token-file access: passive broker status only.
        sock = self.home / '.selfrun-server/drive-auth-token.json.service.sock'
        try:
            import socket
            with socket.socket(socket.AF_UNIX) as s:
                s.settimeout(3)
                s.connect(str(sock))
                s.sendall(b'{"operation":"status"}\n')
                data = b''
                while b'\n' not in data and len(data) < 4096:
                    chunk = s.recv(4096)
                    if not chunk:
                        break
                    data += chunk
            result = json.loads(data.split(b'\n')[0])
            if result.get('ok') and result.get('auth_owner') == 'android' and result.get('state') in (
                    'ready','reauth_required','not_ready','unavailable','not_configured'):
                return result['state']
        except Exception:
            pass
        return 'unknown'

    def response(self):
        with self.lock():
            state = self.load()
        fields = tuple(self.fresh().keys())[:-2]
        row = {key:state[key] for key in fields}
        row['auth_broker_running'] = self.running(LAST)
        row['auth_state'] = self.auth_state()
        if row['state'] == 'power_saving' and row['auth_broker_running']:
            row['state'] = 'blocked'
            row['error_code'] = 'AUTH_BROKER_RUNNING'
        return row

    def request(self, verb, nonce, seq):
        if verb not in ('enable','disable') or not UUID_RE.fullmatch(nonce) or not (0 < seq <= 9007199254740991):
            raise ValueError('BAD_COMMAND')
        with self.lock():
            # Atomic admission with the separate whole-Termux lifecycle gate.
            # This does not change saver selection, journals, or restore behavior.
            lifecycle = self.root / 'lifecycle-v1.json'
            if lifecycle.exists() or lifecycle.is_symlink():
                result = self.execute([str(self.prefix / 'bin/python3'),
                    str(self.home / '.local/lib/termux-watchdog/lifecycle-v1/lifecycle_controller.py'),
                    'mode-gate'])
                if result.returncode != 0:
                    raise ValueError('MODE_ALREADY_TRANSITIONING')
            row = self.load()
            if seq <= row['command_sequence']:
                raise ValueError('STALE_COMMAND')
            desired = 'power_saving' if verb == 'enable' else 'normal'
            leaving_sleep = verb == 'disable' and (
                row.get('requested_mode') == 'power_saving' or
                row.get('state') in ('power_saving', 'draining', 'blocked', 'resuming'))
            if verb == 'enable' and row['state'] not in ('normal','power_saving'):
                raise ValueError('MODE_ALREADY_TRANSITIONING')
            if verb == 'disable' and row['state'] not in ('power_saving','blocked','normal','draining','resuming'):
                raise ValueError('MODE_ALREADY_TRANSITIONING')
            if verb == 'enable' and row['state'] == 'normal':
                row['prior'] = [svc for svc in SERVICES if
                                (self.prefix / 'var/service' / svc).is_dir() and self.running(svc)]
                row['target'] = list(row['prior'])
            row.update(command_id=nonce, command_sequence=seq,
                       requested_mode=desired, operation_id=uuid.uuid4().hex,
                       state='draining' if verb == 'enable' else 'resuming',
                       recovery_suppressed=True, error_code='', revision=row['revision']+1,
                       pending_services=len(row['target']))
            if leaving_sleep:
                # The mode worker starts asynchronously only after this succeeds.
                self.persist_resume_fence(row)
            self.save(row)
            return row['operation_id']

    def current(self, operation_id):
        with self.lock():
            record = self.load()
            return record if record['operation_id'] == operation_id else None

    def update(self, operation_id, **change):
        with self.lock():
            state = self.load()
            if state['operation_id'] != operation_id:
                return False
            state.update(**change)
            state['revision'] += 1
            self.save(state)
            return True

    def bridge_verified(self):
        stat = BRIDGE.stat()
        return stat.st_uid == os.getuid() and stat.st_mode & 0o077 == 0 and \
            hashlib.sha256(BRIDGE.read_bytes()).hexdigest() == BRIDGE_PIN

    def run(self, operation_id):
        with self.lock(self.worklockfile):
            initial = self.current(operation_id)
            if initial is None:
                return
            saver = initial['requested_mode'] == 'power_saving'
            expected = list(initial['prior'])
            work = list(reversed(expected)) if saver else ([LAST] if LAST in expected else []) + [svc for svc in expected if svc != LAST]
            # Broker is stopped last; restored first if it had been running.
            if saver:
                work = [svc for svc in expected if svc != LAST] + ([LAST] if LAST in expected else [])
            for i,svc in enumerate(work):
                current = self.current(operation_id)
                if current is None:
                    return
                if saver:
                    idle_deadline = self.clock() + 120
                    while not self.idle(svc):
                        if self.current(operation_id) is None:
                            return
                        if self.clock() >= idle_deadline:
                            self.update(operation_id, state='blocked', error_code='SERVICE_BUSY_TIMEOUT')
                            return
                        self.wait(2)
                elif svc == LAST and LAST in expected and not self.bridge_verified():
                    self.update(operation_id, state='blocked', error_code='BRIDGE_UNVERIFIED')
                    return
                try:
                    self.change(svc, not saver)
                    if not saver and svc == 'drive-worker':
                        self.restore_stuck_drive_worker()
                except Exception:
                    self.update(operation_id, state='blocked', error_code='SERVICE_TRANSITION_FAILED')
                    return
                self.update(operation_id, pending_services=len(work)-i-1,
                            paused_services=(i+1 if saver else max(0,len(work)-i-1)))
            if saver:
                if self.running(LAST):
                    self.update(operation_id, state='blocked', error_code='AUTH_BROKER_RUNNING')
                    return
                self.update(operation_id, state='power_saving', pending_services=0,
                            paused_services=len(expected), error_code='', recovery_suppressed=True)
            else:
                self.update(operation_id, state='normal', pending_services=0,
                            paused_services=0, error_code='', recovery_suppressed=False)

    def allowed_recovery(self):
        with self.lock():
            row=self.load()
        return row['state'] == 'normal' and not row['recovery_suppressed']


def main():
    if len(sys.argv) == 2 and sys.argv[1] == 'gate':
        sys.exit(0 if Controller().allowed_recovery() else 1)
    if len(sys.argv) == 3 and sys.argv[1] == 'worker':
        Controller().run(sys.argv[2])
        return
    if len(sys.argv) < 2 or sys.argv[1] not in ('status','enable','disable'):
        sys.exit(2)
    ctl = Controller()
    cmd=sys.argv[1]
    try:
        if cmd != 'status':
            if len(sys.argv) != 4:
                raise ValueError('ARGS')
            op=ctl.request(cmd,sys.argv[2],int(sys.argv[3]))
            subprocess.Popen([sys.executable, __file__, 'worker', op],
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
        print(json.dumps(ctl.response(),separators=(',', ':')))
    except Exception:
        sys.exit(3)


if __name__ == '__main__':
    main()
