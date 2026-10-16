"""dev11 clock regression fixtures; run only in the approved Termux workspace.

Clock values, process identities and signaling are isolated. No product startup,
shutdown, signal or real suspend is performed. Apply beside test_lifecycle.py.
"""
import errno
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import test_lifecycle as fixtures

core = fixtures.core
lifecycle = fixtures.lifecycle


class BootTimeValue(unittest.TestCase):
    def provider(self, *, value=123.456, error=None):
        return SimpleNamespace(
            CLOCK_BOOTTIME=object(),
            clock_gettime=Mock(return_value=value, side_effect=error),
            monotonic=Mock(side_effect=AssertionError('monotonic fallback forbidden')),
            time=Mock(side_effect=AssertionError('wall-clock fallback forbidden')))

    def unavailable(self, provider):
        with patch.object(core, 'time', provider):
            with self.assertRaises(core.Failure) as caught:
                core.boot_time()
        self.assertEqual('BOOT_ID_UNAVAILABLE', caught.exception.code)
        if hasattr(provider, 'monotonic'):
            provider.monotonic.assert_not_called()
        if hasattr(provider, 'time'):
            provider.time.assert_not_called()

    def test_normal_values_use_the_named_boottime_clock(self):
        for value in (0.0, 0.001, 123.456, 1_000_000.25):
            with self.subTest(value=value):
                provider = self.provider(value=value)
                with patch.object(core, 'time', provider):
                    self.assertEqual(value, core.boot_time())
                provider.clock_gettime.assert_called_once_with(provider.CLOCK_BOOTTIME)
                provider.monotonic.assert_not_called()
                provider.time.assert_not_called()

    def test_procfs_eacces_is_never_encountered_because_no_file_is_read(self):
        provider = self.provider(value=321.125)
        denied = PermissionError(errno.EACCES, 'fixture procfs denied')
        with patch.object(core, 'time', provider), \
             patch.object(Path, 'read_text', side_effect=denied) as reading, \
             patch('builtins.open', side_effect=denied) as opening, \
             patch.object(core.os, 'open', side_effect=denied) as os_opening:
            self.assertEqual(321.125, core.boot_time(Path('/fixture-denied-proc')))
        reading.assert_not_called()
        opening.assert_not_called()
        os_opening.assert_not_called()

    def test_legacy_proc_parameter_is_accepted_but_not_dereferenced(self):
        class UnreadableProc:
            def __truediv__(self, _name):
                raise AssertionError('proc argument must not be accessed')
        provider = self.provider(value=12.25)
        with patch.object(core, 'time', provider):
            self.assertEqual(12.25, core.boot_time(proc=UnreadableProc()))

    def test_missing_clock_function_fails_closed(self):
        provider = self.provider()
        del provider.clock_gettime
        self.unavailable(provider)

    def test_missing_boottime_constant_fails_closed(self):
        provider = self.provider()
        del provider.CLOCK_BOOTTIME
        self.unavailable(provider)
        provider.clock_gettime.assert_not_called()

    def test_provider_attribute_error_fails_closed(self):
        self.unavailable(self.provider(error=AttributeError('fixture unavailable')))

    def test_system_errors_fail_closed_without_alternate_clock(self):
        for number in (errno.EACCES, errno.ENOSYS, errno.EINVAL):
            with self.subTest(errno=number):
                self.unavailable(self.provider(error=OSError(number, 'fixture failure')))

    def test_provider_value_error_fails_closed(self):
        self.unavailable(self.provider(error=ValueError('fixture invalid clock')))

    def test_nonfinite_and_negative_values_fail_closed(self):
        for value in (float('nan'), float('inf'), float('-inf'), -0.001, -1.0):
            with self.subTest(value=value):
                self.unavailable(self.provider(value=value))


class BootTimeLifecycle(fixtures.Fixture):
    def setUp(self):
        super().setUp()
        self.ctl.close()
        self.boottime = 100.0
        self.clock_id = object()
        self.gettime = Mock(side_effect=lambda _clock_id: self.boottime)
        self.constant_patch = patch.object(core.time, 'CLOCK_BOOTTIME', self.clock_id, create=True)
        self.gettime_patch = patch.object(core.time, 'clock_gettime', self.gettime, create=True)
        self.constant_patch.start()
        self.gettime_patch.start()
        self.addCleanup(self.constant_patch.stop)
        self.addCleanup(self.gettime_patch.stop)
        # Exercise the actual default Lifecycle clock, not the older Fixture's
        # injected clock. Boot UUID and process backend remain isolated fixtures.
        self.ctl = lifecycle.Lifecycle(home=self.home, prefix=self.prefix,
            proc=self.home / 'nonexistent-proc', execute=self.execute,
            wait=self.clock.wait, processes=self.processes, boot=fixtures.BOOT)

    def test_default_controller_clock_does_not_read_procfs(self):
        self.boottime = 201.125
        with patch.object(Path, 'read_text', side_effect=PermissionError(errno.EACCES, 'fixture')) as reading:
            self.assertEqual(201.125, self.ctl.clock())
        reading.assert_not_called()
        self.gettime.assert_called_once_with(self.clock_id)

    def test_prepare_stores_boot_seconds_without_changing_state_or_wire_schema(self):
        self.boottime = 1234.567
        result = self.ctl.begin('prepare-start', fixtures.NONCE, 1)
        self.assertEqual('READY_TO_LAUNCH', result['state'])
        self.assertEqual(16, len(result))
        self.assertFalse(result['final_stop_confirmed'])
        self.assertEqual(core.SCHEMA, self.ctl.row()['schema'])
        self.assertEqual(1234.567, self.ctl.row()['started_monotonic'])
        self.assertEqual(fixtures.NONCE + ' 1', self.ctl.login_ticket())

    def test_existing_dev10_uptime_timestamp_needs_no_conversion(self):
        before = self.save(started_monotonic=100.25)
        self.boottime = 120.25
        self.assertEqual(fixtures.NONCE + ' 1', self.ctl.login_ticket())
        self.assertEqual(before, self.ctl.row())
        self.boottime = 120.251
        self.assertEqual('', self.ctl.login_ticket())
        self.assertEqual(before, self.ctl.row())

    def test_suspend_elapsed_time_expires_start_window_with_monotonic_unchanged(self):
        before = self.save(started_monotonic=100.0)
        # Model suspend: BOOTTIME advances while MONOTONIC does not. This is an
        # injected clock-domain assertion, not a physical device suspend test.
        with patch.object(core.time, 'monotonic', return_value=50.0):
            self.boottime = 119.99
            self.assertEqual(fixtures.NONCE + ' 1', self.ctl.login_ticket())
            self.boottime = 121.0
            self.assertEqual('', self.ctl.login_ticket())
            self.assertFailure('START_TIMEOUT', lambda: self.ctl.login_complete(fixtures.NONCE, 1))
        self.assertEqual(before, self.ctl.row())

    def test_clock_reset_alone_cannot_expire_same_boot_intent_latch(self):
        self.save(phase='OFF_REQUESTED', started_monotonic=100.0)
        self.boottime = 1.0
        self.assertFalse(core.gate(self.ctl.store, fixtures.BOOT))
        self.assertTrue(core.gate(self.ctl.store, fixtures.REBOOT))

    def test_new_shell_tick_boundary_stays_in_suspend_inclusive_domain(self):
        self.boottime = 1500.009
        self.ctl.begin('prepare-start', fixtures.NONCE, 1)
        self.assertEqual(1500.009, self.ctl.row()['started_monotonic'])
        with patch.object(core.time, 'monotonic', return_value=500.0), \
             patch.object(lifecycle.os, 'getppid', return_value=4242), \
             patch.object(lifecycle.os, 'sysconf', return_value=100), \
             patch.object(lifecycle.os, 'readlink', return_value=str(self.prefix / 'bin/bash')):
            # 50001 would be newer under the wrong MONOTONIC clock. 150000 is
            # the same boot tick as preparation. Both must remain rejected.
            for ticks in (50001, 150000):
                self.processes.rows = [core.Identity(4242, self.ctl.uid, ticks, 1, 'S', 'bash')]
                self.assertFailure('LOGIN_NOT_OBSERVED', lambda: self.ctl.login_complete(fixtures.NONCE, 1))
                self.assertFalse(self.ctl.row()['login_observed'])
            self.processes.rows = [core.Identity(4242, self.ctl.uid, 150001, 1, 'S', 'bash')]
            self.ctl.login_complete(fixtures.NONCE, 1)
        self.assertTrue(self.ctl.row()['login_observed'])

    def test_clock_failure_still_precedes_any_prepare_fence_or_latch_write(self):
        self.gettime.side_effect = OSError(errno.EINVAL, 'fixture clock unavailable')
        self.assertFailure('BOOT_ID_UNAVAILABLE', lambda: self.ctl.begin('prepare-start', fixtures.NONCE, 1))
        self.assertIsNone(self.ctl.store.read(core.STATE_FILE))
        self.assertIsNone(self.ctl.store.read('command-resume-fence-v1.json'))


if __name__ == '__main__':
    unittest.main()
