import concurrent.futures, importlib.util, json, os, pathlib, socket, tempfile, threading, time, unittest
spec=importlib.util.spec_from_file_location('deployment',pathlib.Path(__file__).resolve().parents[1]/'deploy.py')
deployment=importlib.util.module_from_spec(spec);spec.loader.exec_module(deployment)

class Server:
    def __init__(self,path,label,delay=0,ok=True):
        self.path=path;self.label=label;self.delay=delay;self.ok=ok;self.accepted=threading.Event()
        self.socket=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.socket.bind(str(path));path.chmod(0o600)
        self.socket.listen(64);self.socket.settimeout(.1);self.stop=threading.Event();self.thread=threading.Thread(target=self.run);self.thread.start()
    def run(self):
        while not self.stop.is_set():
            try:connection,_=self.socket.accept()
            except socket.timeout:continue
            except OSError:break
            threading.Thread(target=self.serve,args=(connection,),daemon=True).start()
    def serve(self,connection):
        with connection:
            connection.recv(16384);self.accepted.set();time.sleep(self.delay)
            connection.sendall(json.dumps({'ok':self.ok,'access_token':'fixture-'+self.label}).encode()+b'\n')
    def close(self):
        self.stop.set();self.socket.close();self.thread.join(1)
        if self.path.exists():self.path.unlink()

class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='auth-route-');self.root=pathlib.Path(self.temporary.name);self.servers=[]
    def tearDown(self):
        for server in self.servers:server.close()
        self.temporary.cleanup()
    def server(self,name,**kwargs):
        s=Server(self.root/name,name,**kwargs);self.servers.append(s);return s
    def test_inflight_old_request_completes_after_atomic_switch(self):
        old=self.server('public',delay=.2);new=self.server('new');route=old.path
        with concurrent.futures.ThreadPoolExecutor() as pool:
            pending=pool.submit(deployment.rpc,route);self.assertTrue(old.accepted.wait(1))
            deployment.atomic_route(route,new.path,deployment.signature(route))
            self.assertEqual(deployment.rpc(route)['access_token'],'fixture-new')
            self.assertEqual(pending.result()['access_token'],'fixture-public')
    def test_continuous_calls_across_repeated_switches_have_no_missing_route(self):
        old=self.server('old-relay');new=self.server('new');route=self.root/'public';os.symlink(old.path,route)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            pending=[pool.submit(lambda:[deployment.rpc(route) for _ in range(50)]) for _ in range(4)]
            for i in range(30):
                deployment.atomic_route(route,new.path if i%2==0 else old.path,deployment.signature(route))
                time.sleep(.001)
            self.assertEqual(sum(len(p.result()) for p in pending),200)
    def test_bad_candidate_never_replaces_old_route(self):
        old=self.server('public');bad=self.server('bad',ok=False);before=deployment.signature(old.path)
        with self.assertRaises(RuntimeError):
            deployment.rpc(bad.path);deployment.atomic_route(old.path,bad.path,before)
        self.assertEqual(deployment.signature(old.path),before)
        self.assertEqual(deployment.rpc(old.path)['access_token'],'fixture-public')
    def test_route_follows_new_socket_inode_after_restart(self):
        old=self.server('public');new=self.server('new')
        deployment.atomic_route(old.path,new.path,deployment.signature(old.path));new.close();self.servers.remove(new)
        newer=self.server('new');self.assertEqual(deployment.rpc(old.path)['access_token'],'fixture-new')
    def test_concurrent_route_change_is_rejected(self):
        old=self.server('public');a=self.server('a');b=self.server('b');before=deployment.signature(old.path)
        deployment.atomic_route(old.path,a.path,before)
        with self.assertRaises(RuntimeError):deployment.atomic_route(old.path,b.path,before)
        self.assertEqual(deployment.rpc(old.path)['access_token'],'fixture-a')
if __name__=='__main__':unittest.main(verbosity=2)
