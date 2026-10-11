import importlib.util, pathlib, socket, unittest
spec=importlib.util.spec_from_file_location('deployment',pathlib.Path(__file__).resolve().parents[1]/'deploy.py')
deployment=importlib.util.module_from_spec(spec);spec.loader.exec_module(deployment)
class DrainTests(unittest.TestCase):
    def test_live_connections_prevent_retirement(self):
        import os
        before=deployment.connections_drained(os.getpid())
        self.assertTrue(before)
        one,two=socket.socketpair()
        try:self.assertFalse(deployment.connections_drained(os.getpid()))
        finally:one.close();two.close()
        self.assertTrue(deployment.connections_drained(os.getpid()))
    def test_live_child_prevents_retirement_but_reusable_idle_child_is_allowed(self):
        import os, subprocess, sys
        child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(10)'])
        try:
            self.assertFalse(deployment.connections_drained(os.getpid()))
            self.assertTrue(deployment.connections_drained(os.getpid(),allow_children=True))
        finally:child.terminate();child.wait()
        self.assertTrue(deployment.connections_drained(os.getpid()))
if __name__=='__main__':unittest.main(verbosity=2)
