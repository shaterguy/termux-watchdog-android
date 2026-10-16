#!/data/data/com.termux/files/usr/bin/python3
"""Exact-source-bound installer. Default is read-only plan; never starts services.

Only run by the parent's approved MANUAL Termux route after review and fixtures.
Unknown startup content, custom login, symlinks, ownership or mode fail closed.
"""
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import uuid

HOME = Path('/data/data/com.termux/files/home')
PREFIX = Path('/data/data/com.termux/files/usr')
PACKAGE = Path(__file__).resolve().parents[1]
UID = os.getuid()
PREVIOUS_PHONE_CONTROLLER_SHA256 = '10160caa2f81247232e73bcbf7c92fbc7d622c8c99874cf5ddf74498ce9e2aaf'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def helper_previous(device, name):
    # One reviewed, already-installed PHONE controller revision only. No CLI
    # override, version wildcard, or allowance for arbitrary previous helpers.
    if device == 'PHONE' and name == 'lifecycle_controller.py':
        return PREVIOUS_PHONE_CONTROLLER_SHA256
    return None


def source_accepted(row, before, after):
    return before is None or digest(before) in (
        row['before'], digest(after), row.get('previous_before'))


def parent_fd(path, create=False):
    base = HOME if path.is_relative_to(HOME) else PREFIX
    relative = path.relative_to(base)
    descriptor = os.open(base, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        item = os.fstat(descriptor)
        if item.st_uid != UID or item.st_mode & 0o022:
            raise ValueError('UNSAFE_BASE')
        for part in relative.parts[:-1]:
            if create:
                try:
                    os.mkdir(part, 0o700, dir_fd=descriptor)
                except FileExistsError:
                    pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            item = os.fstat(descriptor)
            if item.st_uid != UID or item.st_mode & 0o022:
                raise ValueError('UNSAFE_PARENT')
        return descriptor
    except Exception:
        os.close(descriptor)
        raise


def read_target(path):
    directory = parent_fd(path)
    try:
        descriptor = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
        with os.fdopen(descriptor, 'rb') as stream:
            item = os.fstat(stream.fileno())
            if not stat.S_ISREG(item.st_mode) or item.st_uid != UID or \
                    item.st_nlink != 1 or item.st_mode & 0o022:
                raise ValueError('UNSAFE_FILE')
            return stream.read(), stat.S_IMODE(item.st_mode)
    finally:
        os.close(directory)


def write_target(path, raw, mode):
    directory = parent_fd(path, create=True)
    temporary = path.name + '.lifecycle-' + uuid.uuid4().hex
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             mode, dir_fd=directory)
        with os.fdopen(descriptor, 'wb') as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path.name, src_dir_fd=directory, dst_dir_fd=directory)
        os.fsync(directory)
        if read_target(path) != (raw, mode):
            raise ValueError('READBACK_FAILED')
    finally:
        try:
            os.unlink(temporary, dir_fd=directory)
        except FileNotFoundError:
            pass
        os.close(directory)


def plan(device):
    if os.environ.get('HOME') != str(HOME) or os.environ.get('PREFIX') != str(PREFIX) or UID <= 0:
        raise ValueError('TERMUX_ONLY')
    # These are the verified ordinary Bash login chain. Custom paths need review.
    guards = {
        HOME / '.profile': '398ff311f7a19bb7d5ec63a38cfef98320248906534c7765c0d46132a180f48c',
        PREFIX / 'etc/profile': '4a6e4a44dc58c13d1707b3e37c79dae5ccd04568d44b71264868c97fbfd8c63b',
        PREFIX / 'etc/bash.bashrc': '9a3a7241109cf3d9b4ff80d2c8f010d9dfc9f36faa93f2751574194c90541f12',
        PREFIX / 'etc/termux-login.sh': 'e5ce10bfc16da182e0b754c28d1c279a18835e27f825cdbee710f115d75d4f84',
        PREFIX / 'etc/profile.d/start-services.sh': '8c8b8c5222a74bd037cb059ccbbedd4d94cf1f0b1b76b2df8cfa220b9de9d3f8',
        PREFIX / 'bin/login': '3c7401a76d219b4bc96350c2af08741d3c6bd71ca248196ae3e916603611d606',
    }
    for path, expected in guards.items():
        if digest(read_target(path)[0]) != expected:
            raise ValueError('LOGIN_CHAIN_CHANGED')
    for path in (HOME / '.bash_profile', HOME / '.bash_login', HOME / '.termux/shell'):
        if path.exists() or path.is_symlink():
            raise ValueError('CUSTOM_LOGIN')
    state = HOME / '.termux-watchdog'
    item = state.lstat()
    if not stat.S_ISDIR(item.st_mode) or item.st_uid != UID or item.st_mode & 0o077:
        raise ValueError('PRIVATE_STATE_PRECONDITION')
    rows = json.loads((PACKAGE / 'tools/startup-install-spec.json').read_text())[device]
    for name in ('lifecycle_core.py', 'lifecycle_controller.py'):
        rows.append(dict(target=str(HOME / '.local/lib/termux-watchdog/lifecycle-v1' / name),
                         source='termux/' + name, before=None, mode=0o600,
                         previous_before=helper_previous(device,name)))
    for name in ('termux-watchdog-lifecycle', 'termux-watchdog-lifecycle-internal'):
        rows.append(dict(target=str(HOME / 'bin' / name),
                         source='termux/termux-watchdog-lifecycle', before=None, mode=0o700))
    result = []
    for row in rows:
        path = Path(row['target'])
        raw = (PACKAGE / row['source']).read_bytes()
        if 'after' in row and digest(raw) != row['after']:
            raise ValueError('PATCH_PACKAGE_CHANGED')
        try:
            before, mode = read_target(path)
        except FileNotFoundError:
            if row['before'] is not None:
                raise ValueError('REQUIRED_FILE_MISSING') from None
            before, mode = None, row['mode']
        if not source_accepted(row,before,raw):
            raise ValueError('UNKNOWN_SOURCE_CONTENT')
        result.append(dict(path=path, before=before, after=raw, mode=mode))
    return result


def apply(rows):
    # All preconditions above are checked before creating backups or source files.
    backup = HOME / '.local/share/termux-watchdog' / ('lifecycle-backup-' + uuid.uuid4().hex)
    changed, journal = [], []
    try:
        # Helpers first; startup callers and mode admission guard follow.
        rows = sorted(rows, key=lambda row: row['before'] is not None)
        for index, row in enumerate(rows):
            if row['before'] == row['after']:
                continue
            # Recheck immediately before the atomic replace; no silent overwrite.
            try:
                now = read_target(row['path'])[0]
            except FileNotFoundError:
                now = None
            if now != row['before']:
                raise ValueError('SOURCE_CHANGED_DURING_INSTALL')
            if row['before'] is not None:
                write_target(backup / str(index), row['before'], 0o600)
            journal.append(dict(target=str(row['path']), original=str(index), mode=row['mode'],
                                before=digest(row['before']) if row['before'] is not None else None,
                                after=digest(row['after'])))
            write_target(backup / 'manifest.json', (json.dumps(journal,indent=2)+'\n').encode(),0o600)
            changed.append(row)
            write_target(row['path'], row['after'], row['mode'])
    except Exception:
        for row in reversed(changed):
            if row['before'] is not None:
                current = read_target(row['path'])[0]
                if current == row['after']:
                    write_target(row['path'], row['before'], row['mode'])
        # New unreferenced helper files remain inert; no destructive cleanup.
        raise
    return str(backup) if journal else ''


def main():
    if len(sys.argv) not in (2,3) or sys.argv[1] not in ('PHONE','TABLET') or \
            (len(sys.argv)==3 and sys.argv[2] != '--apply'):
        return 2
    rows = plan(sys.argv[1])
    report = dict(schema='termux-watchdog-lifecycle-install-v1', device=sys.argv[1],
                  apply=len(sys.argv)==3, changed=sum(row['before'] != row['after'] for row in rows),
                  source_paths=[str(row['path']) for row in rows], services_touched=False)
    if len(sys.argv)==3:
        report['rollback_backup'] = apply(rows)
    print(json.dumps(report,sort_keys=True))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError) as error:
        code = str(error) if type(error) is ValueError and str(error).isupper() else 'IO_OR_SPEC_ERROR'
        print(json.dumps(dict(schema='termux-watchdog-lifecycle-install-v1',error=code)))
        sys.exit(1)
