"""Private lifecycle state and pidfd-only process control.

This module has no import-time process or filesystem effects. Test adapters must
never call a live process backend. An unavailable pidfd is a hard stop boundary.
"""
import ctypes
import errno
import json
import math
import os
import re
import select
import signal
import stat
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

SCHEMA = 'termux-watchdog-lifecycle-v1'
UUID = re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\Z')
MAX_SEQUENCE = 9007199254740991
HOME = Path('/data/data/com.termux/files/home')
PREFIX = Path('/data/data/com.termux/files/usr')
STATE_FILE = 'lifecycle-v1.json'


class Failure(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def boot_identity(proc=Path('/proc')):
    try:
        value = (proc / 'sys/kernel/random/boot_id').read_text().strip()
        if not UUID.fullmatch(value):
            raise ValueError()
        return value
    except (OSError, ValueError):
        raise Failure('BOOT_ID_UNAVAILABLE') from None


def boot_time(proc=Path('/proc')):
    # Keep the proc argument for callers; the suspend-inclusive clock does not
    # read procfs. CLOCK_MONOTONIC or wall-clock fallback would change its domain.
    try:
        value = time.clock_gettime(time.CLOCK_BOOTTIME)
        if not math.isfinite(value) or value < 0:
            raise ValueError()
        return value
    except (AttributeError, OSError, ValueError):
        raise Failure('BOOT_ID_UNAVAILABLE') from None


class PrivateStore:
    """Keep an opened, owned directory; never follow state/lock symlinks."""
    def __init__(self, root, uid=None):
        self.uid = os.getuid() if uid is None else uid
        self.root = Path(root)
        try:
            self.root.mkdir(mode=0o700, exist_ok=True)
            self.fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            item = os.fstat(self.fd)
            if item.st_uid != self.uid or item.st_mode & 0o077:
                os.close(self.fd)
                raise Failure('STATE_IO')
        except OSError:
            raise Failure('STATE_IO') from None

    def close(self):
        os.close(self.fd)

    def _open(self, name, flags, mode=0o600, private=True):
        if '/' in name or name in ('', '.', '..'):
            raise Failure('STATE_IO')
        fd = os.open(name, flags | os.O_NOFOLLOW | os.O_CLOEXEC, mode, dir_fd=self.fd)
        meta = os.fstat(fd)
        forbidden = 0o077 if private else 0o022
        if not stat.S_ISREG(meta.st_mode) or meta.st_uid != self.uid or \
                meta.st_mode & forbidden or meta.st_nlink != 1:
            os.close(fd)
            raise Failure('STATE_IO')
        return fd

    def read(self, name, missing=None):
        try:
            fd = self._open(name, os.O_RDONLY)
        except FileNotFoundError:
            return missing
        except OSError:
            raise Failure('STATE_IO') from None
        try:
            with os.fdopen(fd, 'rb') as stream:
                raw = stream.read(8193)
            if len(raw) > 8192:
                raise ValueError()
            value = json.loads(raw)
            if type(value) is not dict:
                raise ValueError()
            return value
        except (OSError, ValueError, UnicodeError):
            raise Failure('STATE_INVALID') from None

    def write(self, name, value):
        # Inspect an existing destination without following a symlink first.
        self.read(name)
        temp = name + '.' + uuid.uuid4().hex
        try:
            raw = (json.dumps(value, ensure_ascii=True, separators=(',', ':')) + '\n').encode('ascii')
            if len(raw) > 8192:
                raise Failure('STATE_IO')
            fd = self._open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp, name, src_dir_fd=self.fd, dst_dir_fd=self.fd)
            os.fsync(self.fd)
            if self.read(name) != value:
                raise Failure('STATE_IO')
        except OSError:
            raise Failure('STATE_IO') from None
        finally:
            try:
                os.unlink(temp, dir_fd=self.fd)
            except FileNotFoundError:
                pass

    def lock(self, name, blocking=False, seconds=0):
        import fcntl
        try:
            # Preserve the existing saver lock's permissions; it may be0644.
            # It carries no data. Ownership, regularity, nlink and no foreign
            # write remain mandatory. New lifecycle locks are private.
            fd = self._open(name, os.O_RDWR | os.O_CREAT,
                            private=name != 'power-mode-v2.lock')
            deadline = time.monotonic() + seconds
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(0.02)
            return os.fdopen(fd, 'r+b')
        except BlockingIOError:
            os.close(fd)
            raise Failure('BUSY') from None
        except OSError:
            raise Failure('STATE_IO') from None


def validate_state(row):
    if type(row) is not dict or row.get('schema') != SCHEMA or \
            not UUID.fullmatch(str(row.get('boot_id', ''))) or \
            not UUID.fullmatch(str(row.get('command_id', ''))) or \
            type(row.get('command_sequence')) is not int or \
            not 0 < row['command_sequence'] <= MAX_SEQUENCE or \
            row.get('phase') not in ('STOP_PREPARED', 'STOPPING', 'OFF_REQUESTED',
                                     'STOP_FAILED', 'STARTING', 'START_FAILED', 'RUNNING') or \
            type(row.get('started_monotonic')) not in (int, float) or \
            not math.isfinite(row['started_monotonic']) or row['started_monotonic'] < 0 or \
            type(row.get('login_observed')) is not bool:
        raise Failure('STATE_INVALID')
    return row


def gate(store, boot, *, startup=False):
    row = store.read(STATE_FILE)
    if row is None:
        return True
    validate_state(row)
    if row['boot_id'] != boot:
        return True  # A positively read, changed kernel boot identity only.
    return row['phase'] == 'RUNNING' or (startup and row['phase'] == 'STARTING')


@dataclass(frozen=True)
class Identity:
    pid: int
    uid: int
    start_ticks: int
    ppid: int
    state: str
    comm: str

    def same_process(self, other):
        return (self.pid, self.uid, self.start_ticks) == (other.pid, other.uid, other.start_ticks)


def read_identity(directory_fd, pid):
    def read(name):
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
        with os.fdopen(fd, 'rb') as stream:
            raw = stream.read(8193)
        if len(raw) > 8192:
            raise Failure('PROC_UNREADABLE')
        return raw.decode('utf-8', errors='strict')
    try:
        first = read('stat')
        left, right = first.find('('), first.rfind(')')
        columns = first[right + 2:].split()
        if left < 1 or right < left or int(first[:left]) != pid or len(columns) < 20:
            raise ValueError()
        uid_lines = [line.split()[1:] for line in read('status').splitlines() if line.startswith('Uid:')]
        if len(uid_lines) != 1 or len(uid_lines[0]) != 4:
            raise ValueError()
        uids = tuple(map(int, uid_lines[0]))
        if len(set(uids)) != 1:
            raise Failure('PROC_UNREADABLE')
        result = Identity(pid, uids[0], int(columns[19]), int(columns[1]),
                          columns[0], first[left + 1:right])
        second = read('stat')
        second_columns = second[second.rfind(')') + 2:].split()
        if int(second_columns[19]) != result.start_ticks:
            raise ProcessLookupError()
        return result
    except (ValueError, UnicodeError, IndexError):
        raise Failure('PROC_UNREADABLE') from None


class Pidfds:
    """Use public Bionic symbols; no raw syscall numbers or kill(pid) fallback."""
    def __init__(self):
        if callable(getattr(os, 'pidfd_open', None)) and callable(getattr(signal, 'pidfd_send_signal', None)):
            self.open = lambda pid: os.pidfd_open(pid, 0)
            self.send = lambda fd, sig: signal.pidfd_send_signal(fd, sig, None, 0)
            return
        try:
            libc = ctypes.CDLL('libc.so', use_errno=True)
            opening, sending = libc.pidfd_open, libc.pidfd_send_signal
            opening.argtypes, opening.restype = (ctypes.c_int, ctypes.c_uint), ctypes.c_int
            sending.argtypes = (ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint)
            sending.restype = ctypes.c_int
        except (OSError, AttributeError):
            raise Failure('PIDFD_UNAVAILABLE') from None
        def checked(function, *args):
            result = function(*args)
            if result < 0:
                code = ctypes.get_errno()
                raise OSError(code, os.strerror(code))
            return result
        self.open = lambda pid: checked(opening, pid, 0)
        self.send = lambda fd, sig: checked(sending, fd, int(sig), None, 0)

    def check(self):
        try:
            fd = self.open(os.getpid())
            os.close(fd)
        except OSError:
            raise Failure('PIDFD_UNAVAILABLE') from None


class Processes:
    def __init__(self, uid, proc=Path('/proc'), pidfds=None):
        self.uid, self.proc, self.pidfds = uid, Path(proc), pidfds

    def identity(self, pid):
        if type(pid) is not int or pid <= 1:
            raise Failure('PROC_UNREADABLE')
        fd = os.open(self.proc / str(pid), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            return read_identity(fd, pid)
        finally:
            os.close(fd)

    def snapshot(self):
        result = []
        try:
            paths = list(self.proc.iterdir())
        except OSError:
            raise Failure('PROC_UNREADABLE') from None
        for path in paths:
            if not path.name.isdecimal() or int(path.name) <= 1:
                continue
            owner = None
            try:
                owner = path.stat().st_uid
                if owner != self.uid:
                    # Non-dumpable same-UID tasks can have root-owned proc dirs.
                    # Only read UID metadata here, never cmdline/environment.
                    try:
                        with open(path / 'status', 'r') as stream:
                            visible = stream.read(8192)
                        uid_lines = [line.split()[1:] for line in visible.splitlines()
                                     if line.startswith('Uid:')]
                        if len(uid_lines) != 1 or str(self.uid) not in uid_lines[0]:
                            continue
                        owner = self.uid  # Known same-UID unreadability must fail closed.
                    except (PermissionError, FileNotFoundError, ProcessLookupError):
                        continue
                item = self.identity(int(path.name))
                if item.uid == self.uid and item.state not in ('Z', 'X', 'x'):
                    result.append(item)
            except (FileNotFoundError, ProcessLookupError):
                continue
            except PermissionError:
                if owner == self.uid:
                    raise Failure('PROC_UNREADABLE') from None
        # This is the readable procfs ownership scope, never a system-global claim.
        return result

    def signal(self, observed, sig):
        if sig not in (signal.SIGTERM, signal.SIGKILL) or observed.pid <= 1 or \
                observed.pid == os.getpid() or observed.uid != self.uid:
            raise Failure('UID_MISMATCH')
        directory_fd = descriptor = None
        try:
            # A retained proc directory cannot silently retarget a reused PID.
            directory_fd = os.open(self.proc / str(observed.pid),
                                   os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            before = read_identity(directory_fd, observed.pid)
            if not observed.same_process(before):
                return False
            descriptor = self.pidfds.open(observed.pid)
            after = read_identity(directory_fd, observed.pid)
            if not observed.same_process(after) or after.uid != self.uid:
                return False
            self.pidfds.send(descriptor, sig)
            return True
        except (FileNotFoundError, ProcessLookupError):
            return False
        except OSError as error:
            if error.errno == errno.ESRCH:
                return False
            raise Failure('PIDFD_UNAVAILABLE') from None
        finally:
            if descriptor is not None:
                os.close(descriptor)
            if directory_fd is not None:
                os.close(directory_fd)

    def role(self, item, prefix):
        # Names select scheduling/control roles only, never ownership/kill scope.
        if item.comm == 'com.termux':
            return 'app'
        try:
            executable = os.readlink(self.proc / str(item.pid) / 'exe')
            if executable == str(prefix / 'bin/runsv'):
                return 'runsv'
            if executable != str(prefix / 'bin/runsvdir'):
                return 'other'
            with open(self.proc / str(item.pid) / 'cmdline', 'rb') as stream:
                raw = stream.read(4097)
            args = raw.rstrip(b'\0').split(b'\0')
            if len(raw) <= 4096 and args == [str(prefix / 'bin/runsvdir').encode(),
                                           str(prefix / 'var/service').encode()]:
                return 'supervisor'
            return 'other'
        except (FileNotFoundError, ProcessLookupError):
            return 'gone'
        except OSError:
            raise Failure('PROC_UNREADABLE') from None


def wait_pidfd(fd, seconds):
    poller = select.poll()
    poller.register(fd, select.POLLIN | select.POLLHUP)
    return bool(poller.poll(max(0, int(seconds * 1000))))
