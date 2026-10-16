#!/data/data/com.termux/files/usr/bin/python3
"""User-triggered lifecycle entry; fixed actions and truthful pre-final results."""
from contextlib import closing
from datetime import datetime, timezone
import json
import os
import signal
import stat
import subprocess
import sys
import time
import uuid
from pathlib import Path

from lifecycle_core import (Failure, HOME, PREFIX, SCHEMA, STATE_FILE, UUID,
                            MAX_SEQUENCE, PrivateStore, Pidfds, Processes,
                            boot_identity, boot_time, gate, validate_state, wait_pidfd)

ACTIONS = ('prepare-stop', 'commit-stop', 'prepare-start', 'check-start')
MODE_LOCK = 'power-mode-v2.lock'
WORK_LOCK = 'lifecycle-work-v1.lock'
STARTUP_LOCK = 'lifecycle-startup-v1.lock'
MODE_FILE = 'power-mode-v2.json'


def run(args, seconds=5):
    return subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, text=True, timeout=seconds,
                          check=False)


def package_uid(expected, own_uid, execute=run):
    if type(expected) is not int or expected <= 0 or own_uid != expected:
        raise Failure('UID_MISMATCH')
    try:
        result = execute(['/system/bin/cmd', 'package', 'list', 'packages', '-U',
                          '--user', str(own_uid // 100000), 'com.termux'])
        if result.returncode != 0 or len(result.stdout) > 16384:
            raise ValueError()
        values = [int(line.split(' uid:')[1]) for line in result.stdout.splitlines()
                  if line.startswith('package:com.termux uid:')]
        if values != [expected]:
            raise Failure('UID_MISMATCH')
    except (OSError, ValueError, subprocess.SubprocessError, IndexError):
        raise Failure('PACKAGE_UID_UNAVAILABLE') from None
    return expected


def saver_normal(store):
    row = store.read(MODE_FILE)
    if row is None:
        # Match mode_controller.load(): before first saver use, absence means
        # fresh normal. Do not create or mutate the existing saver journal.
        return
    if row.get('schema') != 'termux-watchdog-mode-v2':
        raise Failure('SAVER_STATE_UNAVAILABLE')
    if row.get('state') != 'normal' or row.get('requested_mode') != 'normal' or \
            row.get('recovery_suppressed') is not False:
        raise Failure('SAVER_ACTIVE')


def resume_fence(store, home, sequence, operation_id):
    """Same published per-device fence shape as mode_controller; no saver lists."""
    try:
        server_name = (home / 'SERVER_NAME').read_text().strip()
        if not server_name or len(server_name) > 128:
            raise ValueError()
        name = 'command-resume-fence-v1.json'
        previous = store.read(name)
        stamp = time.time_ns() // 1_000_000
        if previous is not None:
            if previous.get('schema') != 'drive-worker-resume-fence-v1' or \
                    previous.get('server_name') != server_name:
                raise ValueError()
            earlier = int(datetime.fromisoformat(previous['resumed_at'].replace('Z', '+00:00')).timestamp() * 1000)
            if stamp <= earlier:
                raise ValueError()
        value = dict(schema='drive-worker-resume-fence-v1', server_name=server_name,
                     resumed_at=datetime.fromtimestamp(stamp / 1000, timezone.utc).isoformat(
                         timespec='milliseconds').replace('+00:00', 'Z'),
                     command_sequence=sequence, operation_id=operation_id)
        store.write(name, value)
    except (OSError, ValueError, KeyError, Failure):
        raise Failure('RESUME_FENCE_FAILED') from None


class Lifecycle:
    def __init__(self, home=HOME, prefix=PREFIX, proc=Path('/proc'),
                 execute=run, wait=time.sleep, clock=time.monotonic,
                 processes=None, uid=None, boot=None):
        self.home, self.prefix, self.proc = Path(home), Path(prefix), Path(proc)
        self.uid = os.getuid() if uid is None else uid
        self.store = PrivateStore(self.home / '.termux-watchdog', self.uid)
        self.execute, self.wait = execute, wait
        self.clock = (lambda: boot_time(self.proc)) if clock is time.monotonic else clock
        self.boot = boot_identity(self.proc) if boot is None else boot
        self.processes = processes or Processes(self.uid, self.proc)

    def close(self):
        self.store.close()

    def row(self):
        value = self.store.read(STATE_FILE)
        return validate_state(value) if value is not None else None

    def matching(self, nonce, sequence):
        row = self.row()
        if row is None or row['boot_id'] != self.boot or \
                row['command_id'] != nonce or row['command_sequence'] != sequence:
            raise Failure('STALE_COMMAND')
        return row

    def response(self, action, nonce, sequence, state, error='', **values):
        result = dict(schema=SCHEMA, action=action, command_id=nonce,
                      command_sequence=sequence, state=state, boot_id=self.boot,
                      termux_uid=self.uid, observed_processes=0, supervisor_count=0,
                      enabled_registrations=0, running_registrations=0,
                      registration_errors=0, login_observed=False,
                      final_stop_confirmed=False, error_code=error,
                      observed_at_ms=time.time_ns() // 1_000_000)
        result.update(values)
        return result

    def begin(self, action, nonce, sequence):
        with self.store.lock(WORK_LOCK), self.store.lock(MODE_LOCK, seconds=1):
            saver_normal(self.store)
            old = self.row()
            if old is not None and old['boot_id'] == self.boot and sequence <= old['command_sequence']:
                raise Failure('STALE_COMMAND')
            started = self.clock()  # Prerequisite observation before any fence write.
            if action == 'prepare-stop':
                if not os.access(self.prefix / 'bin/am', os.X_OK):
                    raise Failure('SERVICE_STOP_UNAVAILABLE')
                pidfds = Pidfds()
                pidfds.check()  # Open/close self only; no capability-test signal.
                count = len(self.processes.snapshot())
                phase = 'STOP_PREPARED'
            else:
                resume_fence(self.store, self.home, sequence, nonce.replace('-', ''))
                count, phase = 0, 'STARTING'
            row = dict(schema=SCHEMA, boot_id=self.boot, command_id=nonce,
                       command_sequence=sequence, phase=phase,
                       started_monotonic=started, login_observed=False,
                       error_code='', observed_processes=count)
            self.store.write(STATE_FILE, row)
        return self.response(action, nonce, sequence,
                             'STOP_PREPARED' if action == 'prepare-stop' else 'READY_TO_LAUNCH',
                             observed_processes=count)

    def commit(self, nonce, sequence):
        # Closing in the parent leaves the flock held by the inherited child fd.
        # Do not explicitly LOCK_UN this shared open file description.
        with self.store.lock(WORK_LOCK) as work:
            with self.store.lock(MODE_LOCK, seconds=1):
                saver_normal(self.store)
                row = self.matching(nonce, sequence)
                if row['phase'] != 'STOP_PREPARED':
                    raise Failure('STALE_COMMAND')
                if not 0 <= self.clock() - row['started_monotonic'] <= 20:
                    raise Failure('PREPARE_EXPIRED')
                pidfds = Pidfds()
                pidfds.check()
                self.processes.pidfds = pidfds
                parent_fd = pidfds.open(os.getpid())
                row.update(phase='STOPPING', error_code='')
                self.store.write(STATE_FILE, row)
            try:
                child = os.fork()
            except OSError:
                os.close(parent_fd)
                raise Failure('INTERNAL') from None
            if child == 0:
                try:
                    def tail_expired(_number, _frame):
                        raise Failure('STOP_TIMEOUT')
                    signal.signal(signal.SIGALRM, tail_expired)
                    signal.setitimer(signal.ITIMER_REAL, 22)
                    os.setsid()
                    devnull = os.open('/dev/null', os.O_RDWR)
                    for number in (0, 1, 2):
                        os.dup2(devnull, number)
                    if devnull > 2:
                        os.close(devnull)
                    if not wait_pidfd(parent_fd, 2):
                        raise Failure('PARENT_EXIT_TIMEOUT')
                    os.close(parent_fd)
                    self.stop_tail(nonce, sequence)
                except Failure as error:
                    self.fail(nonce, sequence, 'STOP_FAILED', error.code)
                except Exception:
                    self.fail(nonce, sequence, 'STOP_FAILED', 'INTERNAL')
                finally:
                    os._exit(0)
            os.close(parent_fd)
        return self.response('commit-stop', nonce, sequence, 'STOP_COMMITTED',
                             observed_processes=row['observed_processes'])

    def fail(self, nonce, sequence, phase, code):
        try:
            with self.store.lock(MODE_LOCK, seconds=1):
                row = self.matching(nonce, sequence)
                row.update(phase=phase, error_code=code)
                self.store.write(STATE_FILE, row)
        except Exception:
            pass  # Keep the last durable suppression state, never clear it.

    def stop_tail(self, nonce, sequence):
        started = self.clock()
        package_uid(self.uid, os.getuid(), self.execute)
        if boot_identity(self.proc) != self.boot:
            raise Failure('BOOT_ID_UNAVAILABLE')
        # Official same-UID internal intent, as used by termux-wake-lock/unlock.
        command = [str(self.prefix / 'bin/am'), 'startservice', '--user',
                   str(self.uid // 100000), '-a', 'com.termux.service_stop',
                   '-n', 'com.termux/com.termux.app.TermuxService']
        try:
            result = self.execute(command)
            if result.returncode != 0:
                raise Failure('SERVICE_STOP_FAILED')
        except (OSError, subprocess.SubprocessError):
            raise Failure('SERVICE_STOP_FAILED') from None
        # service_stop kills registered commands. This detached child was not
        # registered and has closed the result pipes. The launcher already exited.
        # Both observed official Termux versions return START_NOT_STICKY.
        signalled = 0
        quiet_since = None
        while self.clock() - started < 20:
            with self.store.lock(MODE_LOCK, seconds=1):
                row = self.matching(nonce, sequence)
                if row['phase'] != 'STOPPING':
                    raise Failure('STALE_COMMAND')
            current = self.processes.snapshot()
            other = [item for item in current if item.pid != os.getpid() and
                     self.processes.role(item, self.prefix) != 'app']
            if not other:
                if quiet_since is None:
                    quiet_since = self.clock()
                if self.clock() - quiet_since < 0.3:
                    self.wait(0.1)
                    continue
                apps = [item for item in current if item.pid != os.getpid()]
                with self.store.lock(MODE_LOCK, seconds=1):
                    row = self.matching(nonce, sequence)
                    row.update(phase='OFF_REQUESTED', error_code='',
                               observed_processes=len(apps) + 1,
                               signals_attempted=signalled,
                               receipt='NON_APP_QUIET_BEFORE_FINAL_EXIT')
                    self.store.write(STATE_FILE, row)
                # This receipt is intentionally pre-final. Killing the Android
                # process may let Android kill its cgroup, including this helper.
                for item in apps:
                    self.processes.signal(item, signal.SIGKILL)
                return
            quiet_since = None
            sig = signal.SIGTERM if self.clock() - started < 6 else signal.SIGKILL
            # Parent-first is based on this observation, not names or a registry.
            by_pid = {item.pid: item for item in other}
            def depth(item):
                seen, count = set(), 0
                while item.ppid in by_pid and item.ppid not in seen:
                    seen.add(item.ppid)
                    item, count = by_pid[item.ppid], count + 1
                return count
            for item in sorted(other, key=depth):
                signalled += int(self.processes.signal(item, sig))
            self.wait(0.2)
        raise Failure('STOP_TIMEOUT')

    def supervisors(self):
        current = self.processes.snapshot()
        supervisors, orphans = [], []
        for item in current:
            role = self.processes.role(item, self.prefix)
            if role == 'supervisor':
                supervisors.append(item)
            elif role == 'runsv':
                orphans.append(item)
        return supervisors, orphans

    def supervisor_pidfile(self, process):
        """Refresh the platform pidfile from live identity, never use it to kill."""
        directory = descriptor = None
        name = 'service-daemon.pid'
        temporary = name + '.lifecycle-' + uuid.uuid4().hex
        try:
            directory = os.open(self.prefix / 'var/run', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            meta = os.fstat(directory)
            if meta.st_uid != self.uid or meta.st_mode & 0o022:
                raise Failure('STATE_IO')
            try:
                prior = os.stat(name, dir_fd=directory, follow_symlinks=False)
                if not stat.S_ISREG(prior.st_mode) or prior.st_uid != self.uid or \
                        prior.st_nlink != 1 or prior.st_mode & 0o022:
                    raise Failure('STATE_IO')
            except FileNotFoundError:
                pass
            observed = self.processes.identity(process.pid)
            if not process.same_process(observed) or self.processes.role(observed, self.prefix) != 'supervisor':
                raise Failure('SUPERVISOR_UNAVAILABLE')
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=directory)
            raw = (str(process.pid) + '\n').encode('ascii')
            with os.fdopen(descriptor, 'wb') as stream:
                descriptor = None
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, name, src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
            descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
            if os.read(descriptor, 32) != raw:
                raise Failure('STATE_IO')
        except OSError:
            raise Failure('SUPERVISOR_UNAVAILABLE') from None
        finally:
            if descriptor is not None:
                os.close(descriptor)
            if directory is not None:
                try:
                    os.unlink(temporary, dir_fd=directory)
                except FileNotFoundError:
                    pass
                os.close(directory)

    def ensure_supervisor(self):
        with self.store.lock(WORK_LOCK), self.store.lock(STARTUP_LOCK):
            with self.store.lock(MODE_LOCK, seconds=1):
                if not gate(self.store, self.boot, startup=True):
                    raise Failure('BUSY')
                row = self.row()
                if row is not None and row['boot_id'] == self.boot and row['phase'] == 'STARTING':
                    saver_normal(self.store)
                # Ordinary login/reboot keeps the platform supervisor available
                # even with persisted saver state. Existing runit down files
                # govern disabled registrations. Saver's transient `sv down`
                # pause is not asserted to survive a supervisor restart/reboot.
            supervisors, runsv = self.supervisors()
            if len(supervisors) > 1:
                raise Failure('DUPLICATE_SUPERVISORS')
            if not supervisors and runsv:
                raise Failure('ORPHAN_SUPERVISORS')
            if supervisors:
                self.supervisor_pidfile(supervisors[0])
                return
            # The standard supervisor discovers every current registration.
            # No down/control files, service lists, or runtime state are changed.
            subprocess.Popen([str(self.prefix / 'bin/runsvdir'),
                              str(self.prefix / 'var/service')],
                             cwd=self.prefix, stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True, close_fds=True)
            deadline = self.clock() + 3
            while self.clock() < deadline:
                supervisors, _ = self.supervisors()
                if len(supervisors) == 1:
                    self.supervisor_pidfile(supervisors[0])
                    return
                if len(supervisors) > 1:
                    raise Failure('DUPLICATE_SUPERVISORS')
                self.wait(0.1)
            raise Failure('SUPERVISOR_UNAVAILABLE')

    def registration_counts(self, deadline=None):
        enabled = running = errors = 0
        try:
            registrations = list((self.prefix / 'var/service').iterdir())
        except OSError:
            raise Failure('REGISTRATION_ERRORS') from None
        for path in registrations:
            if deadline is not None and self.clock() >= deadline:
                raise Failure('START_TIMEOUT')
            if path.name.startswith('.') or not path.is_dir():
                continue  # runsvdir ignores these entries too.
            valid = (path / 'run').is_file() and os.access(path / 'run', os.X_OK)
            disabled = (path / 'down').exists()
            if not valid:
                errors += 1
                continue
            if disabled:
                continue
            enabled += 1
            try:
                remaining = 1 if deadline is None else min(1, max(0.01, deadline - self.clock()))
                args = [str(self.prefix / 'bin/sv'), 'status', str(path)]
                result = run(args, remaining) if self.execute is run else self.execute(args)
                if result.returncode == 0 and result.stdout.startswith('run:'):
                    running += 1
            except (OSError, subprocess.SubprocessError):
                pass
        return enabled, running, errors

    def login_ticket(self):
        with self.store.lock(MODE_LOCK, seconds=1):
            row = self.row()
            if row and row['boot_id'] == self.boot and row['phase'] == 'STARTING' and \
                    0 <= self.clock() - row['started_monotonic'] <= 20:
                return row['command_id'] + ' ' + str(row['command_sequence'])
        return ''

    def login_complete(self, nonce, sequence):
        with self.store.lock(MODE_LOCK, seconds=1):
            row = self.matching(nonce, sequence)
            if row['phase'] != 'STARTING' or not 0 <= self.clock() - row['started_monotonic'] <= 20:
                raise Failure('START_TIMEOUT')
            shell = self.processes.identity(os.getppid())
            if shell.uid != self.uid or shell.start_ticks <= int(row['started_monotonic'] * os.sysconf('SC_CLK_TCK')) or \
                    os.readlink(self.proc / str(shell.pid) / 'exe') != str(self.prefix / 'bin/bash'):
                raise Failure('LOGIN_NOT_OBSERVED')
            row['login_observed'] = True
            self.store.write(STATE_FILE, row)

    def check_start(self, nonce, sequence):
        deadline = self.clock() + 12
        counts, supervisor_count, error = (0, 0, 0), 0, 'START_TIMEOUT'
        while self.clock() < deadline:
            with self.store.lock(MODE_LOCK, seconds=1):
                saver_normal(self.store)
                row = self.matching(nonce, sequence)
                if row['phase'] != 'STARTING':
                    raise Failure('STALE_COMMAND')
                elapsed = self.clock() - row['started_monotonic']
            if not 0 <= elapsed <= 20:
                break
            supervisors, runsv = self.supervisors()
            supervisor_count = len(supervisors)
            if supervisor_count > 1:
                error = 'DUPLICATE_SUPERVISORS'
                break
            if not supervisors and runsv:
                error = 'ORPHAN_SUPERVISORS'
                break
            counts = self.registration_counts(deadline)
            if row['login_observed'] and supervisor_count == 1:
                if counts[2]:
                    error = 'REGISTRATION_ERRORS'
                    break
                if counts[0] == counts[1]:
                    with self.store.lock(MODE_LOCK, seconds=1):
                        saver_normal(self.store)
                        row = self.matching(nonce, sequence)
                        row.update(phase='RUNNING', error_code='')
                        self.store.write(STATE_FILE, row)
                    return self.response('check-start', nonce, sequence, 'START_OBSERVED',
                                         supervisor_count=1, login_observed=True,
                                         enabled_registrations=counts[0], running_registrations=counts[1])
            if not 0 <= elapsed <= 20:
                break
            self.wait(0.25)
        if error == 'START_TIMEOUT' and not row['login_observed']:
            error = 'LOGIN_NOT_OBSERVED'
        self.fail(nonce, sequence, 'START_FAILED', error)
        return self.response('check-start', nonce, sequence, 'START_PARTIAL', error,
                             supervisor_count=supervisor_count, login_observed=row['login_observed'],
                             enabled_registrations=counts[0], running_registrations=counts[1],
                             registration_errors=counts[2])


def public_request(argv):
    if len(argv) != 5 or argv[0] != 'v1' or argv[1] not in ACTIONS or not UUID.fullmatch(argv[2]):
        raise Failure('BAD_COMMAND')
    try:
        sequence, expected = int(argv[3]), int(argv[4])
        if str(sequence) != argv[3] or str(expected) != argv[4] or \
                not 0 < sequence <= MAX_SEQUENCE or expected <= 0:
            raise ValueError()
    except ValueError:
        raise Failure('BAD_COMMAND') from None
    return argv[1], argv[2], sequence, expected


def internal(argv):
    if len(argv) == 1 and argv[0] in ('mode-gate', 'recovery-gate', 'startup', 'login-ticket'):
        with closing(Lifecycle()) as ctl:
            command = argv[0]
            if command in ('mode-gate', 'recovery-gate'):
                # Read-only, no mode lock: mode-gate is invoked while its caller
                # holds that lock. Atomic writes make this safe and non-deadlocking.
                return 0 if gate(ctl.store, ctl.boot) else 1
            if command == 'startup':
                ctl.ensure_supervisor()
            else:
                print(ctl.login_ticket())
            return 0
    if len(argv) == 3 and argv[0] == 'login-complete' and UUID.fullmatch(argv[1]) and argv[2].isdecimal():
        with closing(Lifecycle()) as ctl:
            ctl.login_complete(argv[1], int(argv[2]))
        return 0
    return 2


def main():
    args = sys.argv[1:]
    if args and args[0] != 'v1':
        try:
            def internal_expired(_number, _frame):
                raise Failure('INTERNAL')
            signal.signal(signal.SIGALRM, internal_expired)
            signal.setitimer(signal.ITIMER_REAL, 8)
            return internal(args)
        except (Failure, OSError, subprocess.SubprocessError):
            return 1
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
    try:
        action, nonce, sequence, expected = public_request(args)
    except Failure:
        return 2
    ctl = None
    try:
        def expired(_number, _frame):
            raise Failure('START_TIMEOUT' if action == 'check-start' else 'INTERNAL')
        signal.signal(signal.SIGALRM, expired)
        signal.setitimer(signal.ITIMER_REAL, 14 if action == 'check-start' else 8)
        package_uid(expected, os.getuid())
        ctl = Lifecycle()
        if action.startswith('prepare-'):
            result = ctl.begin(action, nonce, sequence)
        elif action == 'commit-stop':
            result = ctl.commit(nonce, sequence)
        else:
            result = ctl.check_start(nonce, sequence)
    except (Failure, OSError, subprocess.SubprocessError) as error:
        code = error.code if isinstance(error, Failure) else 'INTERNAL'
        if ctl is not None:
            result = ctl.response(action, nonce, sequence, 'REJECTED', code)
        else:
            result = dict(schema=SCHEMA, action=action, command_id=nonce,
                          command_sequence=sequence, state='REJECTED', boot_id='',
                          termux_uid=0, observed_processes=0, supervisor_count=0,
                          enabled_registrations=0, running_registrations=0,
                          registration_errors=0, login_observed=False,
                          final_stop_confirmed=False, error_code=code,
                          observed_at_ms=time.time_ns() // 1_000_000)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        if ctl is not None:
            ctl.close()
    raw = json.dumps(result, ensure_ascii=True, separators=(',', ':')) + '\n'
    if len(raw.encode('ascii')) > 4096:
        return 3
    sys.stdout.write(raw)
    return 0


if __name__ == '__main__':
    sys.exit(main())
