import sys, json, tempfile, time, uuid, unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'termux'))
from mode_controller import Controller

class ResumeFenceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.home=Path(self.temp.name)
        (self.home/'SERVER_NAME').write_text('Galaxy-S25\n')
        self.controller=Controller(home=self.home,prefix=self.home/'usr')
        row=self.controller.fresh()
        row.update(state='power_saving',requested_mode='power_saving',prior=[],target=[])
        self.controller.save(row)
    def tearDown(self):
        self.temp.cleanup()
    def fence(self):
        return json.loads((self.home/'.termux-watchdog/command-resume-fence-v1.json').read_text())
    def test_resume_persists_exact_device_timestamp_before_wake(self):
        before=time.time()*1000
        self.controller.request('disable',str(uuid.uuid4()),1)
        record=self.fence()
        self.assertEqual(record['schema'],'drive-worker-resume-fence-v1')
        self.assertEqual(record['server_name'],'Galaxy-S25')
        self.assertEqual(record['command_sequence'],1)
        self.assertLessEqual(before-1,int(__import__('datetime').datetime.fromisoformat(record['resumed_at'].replace('Z','+00:00')).timestamp()*1000))
        self.assertEqual(self.controller.load()['state'],'resuming')
    def test_repeated_sleep_resume_advances_cutoff(self):
        self.controller.request('disable',str(uuid.uuid4()),1)
        old=self.fence()
        time.sleep(0.02)
        row=self.controller.load()
        row.update(state='power_saving',requested_mode='power_saving')
        self.controller.save(row)
        self.controller.request('disable',str(uuid.uuid4()),2)
        new=self.fence()
        self.assertGreater(new['resumed_at'],old['resumed_at'])
        self.assertEqual(new['command_sequence'],2)
    def test_no_mode_resume_does_not_stamp_new_fence(self):
        row=self.controller.load()
        row.update(state='normal',requested_mode='normal')
        self.controller.save(row)
        self.controller.request('disable',str(uuid.uuid4()),1)
        self.assertFalse((self.home/'.termux-watchdog/command-resume-fence-v1.json').exists())
    def test_missing_server_identity_fails_closed(self):
        (self.home/'SERVER_NAME').unlink()
        with self.assertRaises(FileNotFoundError):
            self.controller.request('disable',str(uuid.uuid4()),1)
        self.assertEqual(self.controller.load()['state'],'power_saving')
    def test_invalid_existing_fence_blocks_resume_without_overwrite(self):
        loc=self.home/'.termux-watchdog/command-resume-fence-v1.json'
        loc.write_text('broken-json')
        with self.assertRaises(json.JSONDecodeError):
            self.controller.request('disable',str(uuid.uuid4()),1)
        self.assertEqual(loc.read_text(),'broken-json')
        self.assertEqual(self.controller.load()['state'],'power_saving')
    def test_fence_survives_controller_restart(self):
        self.controller.request('disable',str(uuid.uuid4()),1)
        prior=self.fence()
        _ = Controller(home=self.home,prefix=self.home/'usr')
        self.assertEqual(prior,self.fence())

if __name__=='__main__':
    unittest.main()

