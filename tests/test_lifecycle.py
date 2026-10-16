"""Isolated fixtures only: no product process is signalled or started.

Execute only in the approved Termux ACTIVE_WORKSPACE, as instructed by the parent.
"""
import errno
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'termux'))
import lifecycle_core as core
import lifecycle_controller as lifecycle
import mode_controller

NONCE = '12345678-1234-1234-1234-123456789abc'
OTHER = '12345678-1234-1234-1234-123456789abd'
BOOT = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
REBOOT = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeef'


class Clock:
    value = 100.0
    def now(self):
        return self.value
    def wait(self, seconds):
        self.value += seconds


class FakeProcesses:
    def __init__(self, rows=(), roles=None):
        self.rows = list(rows)
        self.roles = roles or {}
        self.sent = []
    def snapshot(self):
        return list(self.rows)
    def role(self, item, _prefix):
        return self.roles.get(item.pid, 'other')
    def identity(self, pid):
        return next(item for item in self.rows if item.pid == pid)
    def signal(self, item, sig):
        self.sent.append((item.pid, sig))
        self.rows = [row for row in self.rows if not row.same_process(item)]
        return True


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name) / 'home'
        self.prefix = Path(self.temp.name) / 'usr'
        self.home.mkdir(mode=0o700)
        for relative in ('bin', 'var/service', 'var/run'):
            (self.prefix / relative).mkdir(parents=True, mode=0o700, exist_ok=True)
        (self.home / 'SERVER_NAME').write_text('fixture-host')
        (self.prefix / 'bin/am').write_text('fixture only; never execute')
        (self.prefix / 'bin/am').chmod(0o700)
        self.clock = Clock()
        self.clock.value = 100.0
        self.processes = FakeProcesses()
        self.commands = []
        def execute(args):
            self.commands.append(args)
            if args[0] == '/system/bin/cmd':
                return SimpleNamespace(returncode=0, stdout=f'package:com.termux uid:{os.getuid()}\n')
            return SimpleNamespace(returncode=0, stdout='run: fixture: (pid 123) 1s\n')
        self.execute = execute
        self.ctl = lifecycle.Lifecycle(home=self.home, prefix=self.prefix,
            execute=execute, clock=self.clock.now, wait=self.clock.wait,
            processes=self.processes, boot=BOOT)
        self.ctl.store.write(lifecycle.MODE_FILE, dict(schema='termux-watchdog-mode-v2',
            state='normal', requested_mode='normal', recovery_suppressed=False))
    def tearDown(self):
        self.ctl.close()
        self.temp.cleanup()
    def row(self, phase='STARTING', nonce=NONCE, sequence=1):
        return dict(schema=core.SCHEMA, boot_id=BOOT, command_id=nonce,
            command_sequence=sequence, phase=phase, started_monotonic=self.clock.now(),
            login_observed=False, error_code='', observed_processes=0)
    def save(self, **changes):
        row = self.row()
        row.update(changes)
        self.ctl.store.write(core.STATE_FILE, row)
        return row
    def assertFailure(self, code, call):
        with self.assertRaises(core.Failure) as caught:
            call()
        self.assertEqual(code, caught.exception.code)


class RequestAndState(Fixture):
    def test_request_is_exact_fixed_enum_and_canonical_numbers(self):
        expected = ('prepare-stop', NONCE, 1, 12345)
        self.assertEqual(expected, lifecycle.public_request(['v1','prepare-stop',NONCE,'1','12345']))
        for args in (['v1','arbitrary',NONCE,'1','12345'],
                     ['v1','prepare-stop',NONCE,'01','12345'],
                     ['v1','prepare-stop',NONCE,'1','12345','extra'],
                     ['v1','prepare-stop','$(command)','1','12345']):
            self.assertFailure('BAD_COMMAND', lambda: lifecycle.public_request(args))
    def test_wire_exact_keys_never_confirms_final_stop(self):
        row = self.ctl.response('commit-stop', NONCE, 1, 'STOP_COMMITTED')
        self.assertEqual(16, len(row))
        self.assertEqual(set('schema action command_id command_sequence state boot_id termux_uid observed_processes supervisor_count enabled_registrations running_registrations registration_errors login_observed final_stop_confirmed error_code observed_at_ms'.split()), set(row))
        self.assertIs(False, row['final_stop_confirmed'])
        self.assertLess(len(json.dumps(row)), 4096)
    def test_same_boot_latch_survives_new_controller(self):
        self.save(phase='OFF_REQUESTED')
        self.assertFalse(core.gate(self.ctl.store, BOOT))
        with lifecycle.closing(lifecycle.Lifecycle(home=self.home, prefix=self.prefix,
                processes=self.processes, boot=BOOT)) as other:
            self.assertFalse(core.gate(other.store, BOOT))
        self.assertTrue(core.gate(self.ctl.store, REBOOT))
    def test_corrupt_latch_never_allows_mode_or_startup(self):
        self.ctl.store.write(core.STATE_FILE, {'schema':'old'})
        self.assertFailure('STATE_INVALID', lambda: core.gate(self.ctl.store, BOOT))
    def test_both_prepare_actions_reject_saver_without_new_gate(self):
        self.ctl.store.write(lifecycle.MODE_FILE, dict(schema='termux-watchdog-mode-v2',
            state='power_saving', requested_mode='power_saving', recovery_suppressed=True))
        for action in ('prepare-stop','prepare-start'):
            self.assertFailure('SAVER_ACTIVE', lambda: self.ctl.begin(action, NONCE, 1))
            self.assertIsNone(self.ctl.store.read(core.STATE_FILE))
    def test_unknown_saver_is_closed(self):
        self.ctl.store.write(lifecycle.MODE_FILE, dict(schema='unknown'))
        self.assertFailure('SAVER_STATE_UNAVAILABLE', lambda: self.ctl.begin('prepare-start',NONCE,1))
    def test_missing_saver_matches_actual_fresh_normal_without_creating_journal(self):
        mode_file = self.ctl.store.root / lifecycle.MODE_FILE
        mode_file.unlink()  # Temporary fixture file only; emulate first use.
        existing = mode_controller.Controller(home=self.home,prefix=self.prefix)
        fresh = existing.load()
        self.assertEqual(existing.fresh(),fresh)
        self.assertEqual(('normal','normal',False),
            (fresh['state'],fresh['requested_mode'],fresh['recovery_suppressed']))
        self.assertFalse(mode_file.exists())
        result = self.ctl.begin('prepare-start',NONCE,1)
        self.assertEqual('READY_TO_LAUNCH',result['state'])
        self.assertEqual('STARTING',self.ctl.row()['phase'])
        self.assertFalse(mode_file.exists())
        self.assertIsNone(self.ctl.store.read(lifecycle.MODE_FILE))
    def test_present_incomplete_saver_state_is_not_fresh_normal(self):
        self.ctl.store.write(lifecycle.MODE_FILE,dict(schema='termux-watchdog-mode-v2',
            state='normal',requested_mode='normal'))
        self.assertFailure('SAVER_ACTIVE',lambda:self.ctl.begin('prepare-start',NONCE,1))
        self.assertIsNone(self.ctl.store.read(core.STATE_FILE))
        self.assertIsNone(self.ctl.store.read('command-resume-fence-v1.json'))
    def test_malformed_saver_json_does_not_become_fresh_normal(self):
        mode_file = self.ctl.store.root / lifecycle.MODE_FILE
        mode_file.write_bytes(b'{malformed fixture')
        self.assertFailure('STATE_INVALID',lambda:self.ctl.begin('prepare-start',NONCE,1))
        self.assertIsNone(self.ctl.store.read(core.STATE_FILE))
        self.assertIsNone(self.ctl.store.read('command-resume-fence-v1.json'))
        self.assertEqual(b'{malformed fixture',mode_file.read_bytes())
    def test_permission_denied_saver_does_not_become_fresh_normal(self):
        mode_file = self.ctl.store.root / lifecycle.MODE_FILE
        original_bytes = mode_file.read_bytes()
        opening = self.ctl.store._open
        def denied(name,*args,**kwargs):
            if name == lifecycle.MODE_FILE:
                raise PermissionError(errno.EACCES,'fixture read denied')
            return opening(name,*args,**kwargs)
        with patch.object(self.ctl.store,'_open',side_effect=denied):
            self.assertFailure('STATE_IO',lambda:self.ctl.begin('prepare-start',NONCE,1))
        self.assertIsNone(self.ctl.store.read(core.STATE_FILE))
        self.assertIsNone(self.ctl.store.read('command-resume-fence-v1.json'))
        self.assertEqual(original_bytes,mode_file.read_bytes())
    def test_stale_sequence_cannot_reverse_gate(self):
        self.save(phase='OFF_REQUESTED', command_sequence=4)
        self.assertFailure('STALE_COMMAND', lambda: self.ctl.begin('prepare-start',NONCE,3))
    def test_start_writes_existing_resume_fence_before_ready(self):
        result = self.ctl.begin('prepare-start', NONCE, 2)
        self.assertEqual('READY_TO_LAUNCH', result['state'])
        fence = self.ctl.store.read('command-resume-fence-v1.json')
        self.assertEqual('drive-worker-resume-fence-v1', fence['schema'])
        self.assertEqual('fixture-host', fence['server_name'])
        self.assertEqual(2, fence['command_sequence'])
        self.assertEqual('STARTING', self.ctl.row()['phase'])
        self.assertFalse(core.gate(self.ctl.store, BOOT))
        self.assertTrue(core.gate(self.ctl.store, BOOT, startup=True))
    def test_resume_fence_failure_leaves_prior_latch(self):
        self.save(phase='OFF_REQUESTED')
        (self.home / 'SERVER_NAME').unlink()
        self.assertFailure('RESUME_FENCE_FAILED', lambda: self.ctl.begin('prepare-start',OTHER,2))
        self.assertEqual('OFF_REQUESTED', self.ctl.row()['phase'])
    def test_clock_prerequisite_failure_precedes_fence_and_gate(self):
        self.ctl.clock = lambda: (_ for _ in ()).throw(core.Failure('BOOT_ID_UNAVAILABLE'))
        self.assertFailure('BOOT_ID_UNAVAILABLE', lambda: self.ctl.begin('prepare-start',NONCE,2))
        self.assertIsNone(self.ctl.store.read('command-resume-fence-v1.json'))
        self.assertIsNone(self.ctl.store.read(core.STATE_FILE))
    def test_commit_rejects_wrong_nonce_before_fork(self):
        self.save(phase='STOP_PREPARED')
        with patch.object(os, 'fork', side_effect=AssertionError('must not fork')):
            self.assertFailure('STALE_COMMAND', lambda: self.ctl.commit(OTHER, 1))
    def test_commit_rejects_expired_prepare_before_fork(self):
        self.save(phase='STOP_PREPARED', started_monotonic=1.0)
        with patch.object(os, 'fork', side_effect=AssertionError('must not fork')):
            self.assertFailure('PREPARE_EXPIRED', lambda: self.ctl.commit(NONCE, 1))
    def test_work_lock_blocks_start(self):
        with self.ctl.store.lock(lifecycle.WORK_LOCK):
            self.assertFailure('BUSY', lambda: self.ctl.begin('prepare-start',NONCE,2))
    def test_atomic_replace_failure_does_not_clear_old_latch(self):
        old = self.save(phase='OFF_REQUESTED')
        with patch.object(os, 'replace', side_effect=OSError(errno.EIO, 'fixture')):
            self.assertFailure('STATE_IO', lambda: self.ctl.store.write(core.STATE_FILE,self.row()))
        self.assertEqual(old, self.ctl.row())
    def test_symlink_state_is_rejected(self):
        target = self.home / 'elsewhere'
        target.write_text('{}')
        (self.ctl.store.root / core.STATE_FILE).symlink_to(target)
        self.assertFailure('STATE_IO', lambda: self.ctl.store.write(core.STATE_FILE,self.row()))
        self.assertEqual('{}', target.read_text())
    def test_no_pidfd_has_no_nonpidfd_fallback(self):
        with patch.object(core.ctypes, 'CDLL', side_effect=OSError()), \
             patch.object(core.os, 'pidfd_open', None, create=True), \
             patch.object(core.signal, 'pidfd_send_signal', None, create=True):
            self.assertFailure('PIDFD_UNAVAILABLE', core.Pidfds)
    def test_package_uid_is_current_user_scoped(self):
        captured = []
        def execute(args):
            captured.append(args)
            return SimpleNamespace(returncode=0,stdout='package:com.termux uid:1012345\n')
        self.assertEqual(1012345, lifecycle.package_uid(1012345,1012345,execute))
        self.assertIn('10', captured[0])
        self.assertFailure('UID_MISMATCH', lambda: lifecycle.package_uid(12345,1012345,execute))


class Startup(Fixture):
    def process(self, pid, role, parent=1):
        row = core.Identity(pid, os.getuid(), 12000, parent, 'S', role)
        self.processes.rows.append(row)
        self.processes.roles[pid] = role
        return row
    def registration(self, name, disabled=False, valid=True):
        path = self.prefix / 'var/service' / name
        path.mkdir()
        if valid:
            (path / 'run').write_text('fixture only; never execute')
            (path / 'run').chmod(0o700)
        if disabled:
            (path / 'down').write_text('preserve fixture')
        return path
    def test_new_registration_is_included_disabled_preserved_invalid_reported(self):
        self.registration('new-arbitrary-service')
        down = self.registration('disabled-arbitrary', disabled=True)
        self.registration('missing-run', valid=False)
        self.assertEqual((1,1,1), self.ctl.registration_counts())
        self.assertEqual('preserve fixture',(down / 'down').read_text())
        self.assertEqual(1,len(self.commands))
    def test_duplicate_supervisors_never_spawn_or_kill(self):
        self.process(500,'supervisor')
        self.process(600,'supervisor')
        with patch.object(lifecycle.subprocess,'Popen',side_effect=AssertionError()):
            self.assertFailure('DUPLICATE_SUPERVISORS',self.ctl.ensure_supervisor)
        self.assertEqual([], self.processes.sent)
    def test_orphan_runsv_never_assumed_cold_start(self):
        self.process(500,'runsv')
        with patch.object(lifecycle.subprocess,'Popen',side_effect=AssertionError()):
            self.assertFailure('ORPHAN_SUPERVISORS',self.ctl.ensure_supervisor)
    def test_stale_pidfile_ignored_and_live_pidfile_refreshed(self):
        (self.prefix / 'var/run/service-daemon.pid').write_text('999999\n')
        self.process(500,'supervisor')
        with patch.object(lifecycle.subprocess,'Popen',side_effect=AssertionError()):
            self.ctl.ensure_supervisor()
        self.assertEqual('500\n',(self.prefix / 'var/run/service-daemon.pid').read_text())
    def test_repeated_startup_has_one_dynamic_supervisor(self):
        def spawn(args, **_kwargs):
            self.assertEqual([str(self.prefix/'bin/runsvdir'),str(self.prefix/'var/service')],args)
            self.process(500,'supervisor')
        with patch.object(lifecycle.subprocess,'Popen',side_effect=spawn) as called:
            self.ctl.ensure_supervisor()
            self.ctl.ensure_supervisor()
        self.assertEqual(1,called.call_count)
    def test_ordinary_reboot_preserves_supervisor_with_persisted_saver(self):
        self.save(boot_id=REBOOT,phase='OFF_REQUESTED')
        self.ctl.store.write(lifecycle.MODE_FILE,dict(schema='termux-watchdog-mode-v2',
            state='power_saving',requested_mode='power_saving',recovery_suppressed=True))
        # Generic disabled registration, independent of the saver. Existing
        # saver uses `sv down`, not a persistent down file. This fixture proves
        # supervisor availability and file preservation, not pause after reboot.
        self.registration('disabled-registration',disabled=True)
        def spawn(_args,**_kwargs):
            self.process(500,'supervisor')
        with patch.object(lifecycle.subprocess,'Popen',side_effect=spawn) as called:
            self.ctl.ensure_supervisor()
        self.assertEqual(1,called.call_count)
        self.assertTrue((self.prefix/'var/service/disabled-registration/down').exists())
        self.assertEqual('power_saving',self.ctl.store.read(lifecycle.MODE_FILE)['state'])
    def test_explicit_lifecycle_startup_still_rejects_saver(self):
        self.save(phase='STARTING')
        self.ctl.store.write(lifecycle.MODE_FILE,dict(schema='termux-watchdog-mode-v2',
            state='power_saving',requested_mode='power_saving',recovery_suppressed=True))
        with patch.object(lifecycle.subprocess,'Popen',side_effect=AssertionError()):
            self.assertFailure('SAVER_ACTIVE',self.ctl.ensure_supervisor)
    def test_startup_success_requires_login_and_runtime(self):
        self.save(login_observed=True)
        self.process(500,'supervisor')
        self.registration('future-service')
        result = self.ctl.check_start(NONCE,1)
        self.assertEqual('START_OBSERVED',result['state'])
        self.assertEqual('RUNNING',self.ctl.row()['phase'])
        self.assertFalse(result['final_stop_confirmed'])
    def test_existing_shell_does_not_make_launcher_success(self):
        self.save()
        self.process(500,'supervisor')
        result = self.ctl.check_start(NONCE,1)
        self.assertEqual('START_PARTIAL',result['state'])
        self.assertEqual('LOGIN_NOT_OBSERVED',result['error_code'])
        self.assertFalse(core.gate(self.ctl.store,BOOT))
    def test_registration_error_is_partial_even_after_prompt(self):
        self.save(login_observed=True)
        self.process(500,'supervisor')
        self.registration('bad-new-item',valid=False)
        result = self.ctl.check_start(NONCE,1)
        self.assertEqual(('START_PARTIAL','REGISTRATION_ERRORS',1),
            (result['state'],result['error_code'],result['registration_errors']))


class SignalIdentity(Fixture):
    def make_proc(self, pid, uid, ticks, ppid=1):
        proc = self.home / 'fixture-proc'
        path = proc / str(pid)
        path.mkdir(parents=True,exist_ok=True)
        columns = ['S',str(ppid)] + ['0'] * 17 + [str(ticks)]
        (path / 'stat').write_text(f'{pid} (fixture child) ' + ' '.join(columns) + '\n')
        (path / 'status').write_text('Uid:\t' + '\t'.join([str(uid)]*4) + '\n')
        return proc
    def test_foreign_uid_is_never_signalled(self):
        processes = core.Processes(os.getuid(),self.home/'absent',SimpleNamespace())
        item = core.Identity(400,os.getuid()+1,100,1,'S','anything')
        self.assertFailure('UID_MISMATCH',lambda: processes.signal(item,signal.SIGTERM))
    def test_zero_negative_and_self_pid_never_used(self):
        processes = core.Processes(os.getuid(),self.home/'absent',SimpleNamespace())
        for pid in (0,-1,os.getpid()):
            item = core.Identity(pid,os.getuid(),100,1,'S','anything')
            self.assertFailure('UID_MISMATCH',lambda: processes.signal(item,signal.SIGTERM))
    def test_pid_reuse_between_observation_and_pin_is_skipped(self):
        proc = self.make_proc(400,os.getuid(),101)
        sender = SimpleNamespace(open=lambda _pid: (_ for _ in ()).throw(AssertionError()),send=None)
        processes = core.Processes(os.getuid(),proc,sender)
        item = core.Identity(400,os.getuid(),100,1,'S','fixture')
        self.assertFalse(processes.signal(item,signal.SIGTERM))
    def test_reparented_identity_uses_pidfd_and_is_in_scope(self):
        proc = self.make_proc(400,os.getuid(),100,ppid=1)
        sent = []
        sender = SimpleNamespace(open=lambda _pid: os.open('/dev/null',os.O_RDONLY),
                                 send=lambda fd,sig: sent.append((fd,sig)))
        processes = core.Processes(os.getuid(),proc,sender)
        item = core.Identity(400,os.getuid(),100,999,'S','fixture')
        self.assertTrue(processes.signal(item,signal.SIGTERM))
        self.assertEqual(signal.SIGTERM,sent[0][1])
    def test_identity_change_after_pin_does_not_send(self):
        proc = self.make_proc(400,os.getuid(),100)
        def opening(_pid):
            self.make_proc(400,os.getuid(),101)
            return os.open('/dev/null',os.O_RDONLY)
        sender = SimpleNamespace(open=opening,send=lambda *_args: self.fail('wrong process signalled'))
        processes = core.Processes(os.getuid(),proc,sender)
        item = core.Identity(400,os.getuid(),100,1,'S','fixture')
        self.assertFalse(processes.signal(item,signal.SIGKILL))
    def test_exited_process_is_idempotent(self):
        processes = core.Processes(os.getuid(),self.home/'absent',SimpleNamespace())
        item = core.Identity(400,os.getuid(),100,1,'S','fixture')
        self.assertFalse(processes.signal(item,signal.SIGTERM))


class StopTail(Fixture):
    def test_rescan_catches_new_reparented_shared_uid_process(self):
        self.save(phase='STOPPING')
        first = core.Identity(400,os.getuid(),100,1,'S','extension')
        second = core.Identity(401,os.getuid(),101,1,'S','future-service')
        self.processes.rows = [first]
        original = self.processes.signal
        def sending(item, sig):
            result = original(item,sig)
            if item.pid==400:
                self.processes.rows.append(second)
            return result
        self.processes.signal = sending
        with patch.object(lifecycle,'boot_identity',return_value=BOOT), \
             patch.object(lifecycle,'package_uid',return_value=os.getuid()):
            self.ctl.stop_tail(NONCE,1)
        self.assertEqual([400,401],[pid for pid,_sig in self.processes.sent])
        self.assertEqual('OFF_REQUESTED',self.ctl.row()['phase'])
        self.assertEqual('NON_APP_QUIET_BEFORE_FINAL_EXIT',self.ctl.row()['receipt'])
        self.assertEqual('com.termux.service_stop',self.commands[0][5])
    def test_service_stop_refusal_sends_no_pidfd_signal(self):
        self.save(phase='STOPPING')
        self.ctl.execute = lambda _args: SimpleNamespace(returncode=1,stdout='')
        with patch.object(lifecycle,'boot_identity',return_value=BOOT), \
             patch.object(lifecycle,'package_uid',return_value=os.getuid()):
            self.assertFailure('SERVICE_STOP_FAILED',lambda:self.ctl.stop_tail(NONCE,1))
        self.assertEqual([],self.processes.sent)


class SaverAdmission(Fixture):
    def test_legacy_readable_mode_lock_preserves_permissions(self):
        path = self.ctl.store.root / lifecycle.MODE_LOCK
        path.write_bytes(b'')
        path.chmod(0o644)
        with self.ctl.store.lock(lifecycle.MODE_LOCK):
            self.assertEqual(0o644, path.stat().st_mode & 0o777)
    def test_mode_request_and_lifecycle_admission_share_lock(self):
        self.save(phase='OFF_REQUESTED')
        def execute(_args):
            self.assertFailure('BUSY',lambda:self.ctl.begin('prepare-start',OTHER,2))
            return SimpleNamespace(returncode=1,stdout='')
        controller = mode_controller.Controller(home=self.home,prefix=self.prefix,execute=execute)
        with self.assertRaisesRegex(ValueError,'MODE_ALREADY_TRANSITIONING'):
            controller.request('enable',NONCE,2)
        self.assertEqual('OFF_REQUESTED',self.ctl.row()['phase'])
    def test_existing_mode_request_rejects_lifecycle_gate_under_mode_lock(self):
        self.save(phase='OFF_REQUESTED')
        called = []
        def execute(args):
            called.append(args)
            return SimpleNamespace(returncode=1,stdout='')
        controller = mode_controller.Controller(home=self.home,prefix=self.prefix,execute=execute)
        with self.assertRaisesRegex(ValueError,'MODE_ALREADY_TRANSITIONING'):
            controller.request('disable',NONCE,2)
        self.assertEqual('mode-gate',called[0][-1])
        self.assertEqual('normal',self.ctl.store.read(lifecycle.MODE_FILE)['state'])


class PromptHook(Fixture):
    def test_scalar_and_array_commands_and_prior_status_survive(self):
        # This executes an isolated Bash fixture only, with a stub helper under
        # the temporary HOME. No login startup files or product helper are run.
        bindir = self.home / 'bin'
        bindir.mkdir()
        stub = bindir / 'termux-watchdog-lifecycle-internal'
        stub.write_text('#!'+str(lifecycle.PREFIX/'bin/bash')+'\n'
            'if [[ $1 == login-ticket ]]; then echo "'+NONCE+' 1"; '
            'else printf "%s\\n" "$*" >>"$HOME/receipt"; fi\n')
        stub.chmod(0o700)
        hook = Path(__file__).resolve().parents[1] / 'termux/lifecycle-prompt-hook.sh'
        for declaration in ("PROMPT_COMMAND='printf scalar'",
                            "PROMPT_COMMAND=('printf first' 'printf second')",
                            "declare -ax PROMPT_COMMAND=('printf first' 'printf second')"):
            code = declaration+'; source "$1"; declare -p PROMPT_COMMAND; '
            code += '(exit 7); _tw_lifecycle_first_prompt; echo STATUS:$?; _tw_lifecycle_first_prompt'
            result = subprocess.run([str(lifecycle.PREFIX/'bin/bash'),'--noprofile','--norc',
                '-ilc',code,'fixture',str(hook)],env={**os.environ,'HOME':str(self.home)},
                capture_output=True,text=True,timeout=3,check=False)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertIn('STATUS:7',result.stdout)
            self.assertIn('_tw_lifecycle_first_prompt',result.stdout)
            self.assertIn('scalar' if 'scalar' in declaration else 'second',result.stdout)
        self.assertEqual(3,len((self.home/'receipt').read_text().splitlines()))


class InstallerUpgrade(unittest.TestCase):
    def test_only_exact_reviewed_phone_controller_is_an_upgrade_source(self):
        filename = Path(__file__).resolve().parents[1] / 'tools/install-lifecycle.py'
        spec = importlib.util.spec_from_file_location('lifecycle_install_fixture',filename)
        installer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(installer)  # Definitions only; main() is not run.
        old = '10160caa2f81247232e73bcbf7c92fbc7d622c8c99874cf5ddf74498ce9e2aaf'
        self.assertEqual(old,installer.helper_previous('PHONE','lifecycle_controller.py'))
        self.assertIsNone(installer.helper_previous('TABLET','lifecycle_controller.py'))
        self.assertIsNone(installer.helper_previous('PHONE','lifecycle_core.py'))
        phone = dict(before=None,previous_before=old)
        tablet = dict(before=None,previous_before=None)
        with patch.object(installer,'digest',side_effect=lambda value:value):
            self.assertTrue(installer.source_accepted(phone,old,'new-sha'))
            self.assertTrue(installer.source_accepted(phone,'new-sha','new-sha'))
            self.assertTrue(installer.source_accepted(tablet,None,'new-sha'))
            self.assertFalse(installer.source_accepted(phone,'unknown-sha','new-sha'))
            self.assertFalse(installer.source_accepted(tablet,old,'new-sha'))


if __name__ == '__main__':
    unittest.main()
