#!/data/data/com.termux/files/usr/bin/python
"""Transactional, supervised authentication handoff; never modifies Google grants.
Run only against a separately staged, hash-verified runtime on the current device.
"""
import argparse, hashlib, json, os, pathlib, re, signal, socket, stat, subprocess, threading, time, urllib.request

HOME=pathlib.Path.home()
PREFIX=pathlib.Path(os.environ.get('PREFIX',str(HOME.parent/'usr')))
VERSION='1.0.6-dev1'
CANONICAL=HOME/'.selfrun-server/drive-auth-token.json.service.sock'
RUNTIME=HOME/'.local/lib/termux-google-auth'/('watchdog-v'+VERSION)
SERVICE=PREFIX/'var/service/termux-google-auth'
STAGE_NAME='termux-google-auth-stage-106'
STATE=HOME/'.local/state/auth-power-106'
A=HOME/'.selfrun-server/ap106a.sock'
B=HOME/'.selfrun-server/ap106b.sock'
F=HOME/'.selfrun-server/ap106f.sock'


def signature(path):
    s=path.lstat()
    return (s.st_dev,s.st_ino,s.st_mode,s.st_mtime_ns,os.readlink(path) if path.is_symlink() else None)


def atomic_route(canonical,target,expected):
    """A same-directory rename never exposes an absent public route."""
    st=target.stat()
    if not stat.S_ISSOCK(st.st_mode) or st.st_uid!=os.getuid() or st.st_mode&0o077:
        raise RuntimeError('UNSAFE_TARGET_SOCKET')
    temporary=canonical.with_name(canonical.name+'.route-'+str(os.getpid()))
    if temporary.exists() or temporary.is_symlink():raise RuntimeError('STALE_ROUTE_TEMP')
    try:
        os.symlink(str(target),temporary)
        if signature(canonical)!=expected:raise RuntimeError('CONCURRENT_ROUTE_CHANGE')
        os.replace(temporary,canonical)
    finally:
        if temporary.is_symlink():temporary.unlink()


def rpc(path,operation='access',timeout=12):
    with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as connection:
        connection.settimeout(timeout);connection.connect(str(path))
        connection.sendall(json.dumps({'operation':operation}).encode()+b'\n')
        raw=bytearray()
        while b'\n' not in raw:
            chunk=connection.recv(4096)
            if not chunk:raise RuntimeError('AUTH_CONNECTION_ENDED')
            raw.extend(chunk)
            if len(raw)>32768:raise RuntimeError('AUTH_RESPONSE_OVERSIZED')
        value=json.loads(bytes(raw).split(b'\n',1)[0])
        if not value.get('ok'):raise RuntimeError('AUTH_REQUEST_FAILED')
        if operation=='access' and not isinstance(value.get('access_token'),str):raise RuntimeError('AUTH_TOKEN_MISSING')
        return value


def google_check(path):
    value=rpc(path)
    request=urllib.request.Request('https://www.googleapis.com/drive/v3/files/1hUsejZjBn8xLr-9lazEdmgHzUrJttHM8eeyW7J5qx9c?fields=id',
                                  headers={'Authorization':'Bearer '+value['access_token']})
    with urllib.request.urlopen(request,timeout=15) as response:
        if response.status!=200 or json.load(response).get('id')!='1hUsejZjBn8xLr-9lazEdmgHzUrJttHM8eeyW7J5qx9c':
            raise RuntimeError('GOOGLE_READ_FAILED')
    return 200


def sv(action,path=SERVICE,check=True):
    result=subprocess.run([str(PREFIX/'bin/sv'),'-w','15',action,str(path)],capture_output=True,text=True,timeout=20)
    if check and result.returncode:raise RuntimeError('SUPERVISOR_'+action.upper()+'_FAILED')
    return result.stdout.strip()


def pid_for(path=SERVICE):
    match=re.search(r'\(pid (\d+)\)',sv('status',path,check=False))
    if not match:raise RuntimeError('SUPERVISED_PID_MISSING')
    return int(match.group(1))


def identity(pid,version):
    try:return ('/watchdog-v'+version+'/main.mjs').encode() in pathlib.Path('/proc',str(pid),'cmdline').read_bytes()
    except FileNotFoundError:return False


def wait_for(predicate,seconds=25):
    deadline=time.monotonic()+seconds;last=None
    while time.monotonic()<deadline:
        try:
            value=predicate()
            if value:return value
        except (OSError,RuntimeError,ValueError) as error:last=type(error).__name__
        time.sleep(.2)
    raise RuntimeError('READINESS_TIMEOUT'+('_'+last if last else ''))


def connections_drained(pid,allow_children=False):
    sockets=0
    for f in pathlib.Path('/proc',str(pid),'fd').iterdir():
        if int(f.name)<=2:continue  # stdio belongs to runit/DW, not auth clients
        try:sockets+=os.readlink(f).startswith('socket:[')
        except FileNotFoundError:pass
    if sockets>1:return False
    if allow_children:return True
    # Android kernels need not expose /proc/PID/task/PID/children. Inspect only
    # same-UID process metadata; unreadable own metadata does not imply drained.
    for process in pathlib.Path('/proc').iterdir():
        if not process.name.isdigit():continue
        try:
            if process.stat().st_uid!=os.getuid():continue
            fields=(process/'stat').read_text().rsplit(')',1)[1].split()
            if int(fields[1])==pid:return False
        except FileNotFoundError:continue
    return True


def drained_old(pid):
    if not identity(pid,'1.0.5'):raise RuntimeError('OLD_PID_IDENTITY_CHANGED')
    return connections_drained(pid)


def write_run(path,text):
    temporary=path.with_name(path.name+'.auth-power-new')
    with open(temporary,'x') as f:f.write(text);f.flush();os.fsync(f.fileno())
    temporary.chmod(0o700);os.replace(temporary,path)


def run_text(original,version,socket_path,stage=False):
    if original.count('/watchdog-v1.0.5/main.mjs')!=1 or 'TERMUX_GOOGLE_AUTH_SOCKET' in original:
        raise RuntimeError('UNEXPECTED_BASELINE_RUN')
    updated=original.replace('/watchdog-v1.0.5/main.mjs','/watchdog-v'+version+'/main.mjs')
    exports='export TERMUX_GOOGLE_AUTH_SOCKET="'+str(socket_path)+'"\n'
    if stage:exports+='export TERMUX_GOOGLE_AUTH_TOKEN_FILE="'+str(HOME/'.selfrun-server/ap106-stage-lock')+'"\n'
    return updated.replace('exec ',exports+'exec ',1)


class Monitor:
    def __init__(self):self.stop=threading.Event();self.total=0;self.errors=0;self.max_ms=0;self.thread=threading.Thread(target=self.run,daemon=True)
    def run(self):
        while not self.stop.is_set():
            start=time.monotonic()
            try:rpc(CANONICAL)
            except Exception:self.errors+=1
            self.total+=1;self.max_ms=max(self.max_ms,round((time.monotonic()-start)*1000))
            self.stop.wait(.1)
    def finish(self):self.stop.set();self.thread.join(13);return {'requests':self.total,'failures':self.errors,'max_latency_ms':self.max_ms}


def deploy():
    os.umask(0o077)
    if STATE.exists():raise RuntimeError('EXISTING_TRANSACTION_REQUIRES_READBACK')
    STATE.mkdir(parents=True,mode=0o700)
    def record(event,**fields):
        value={'event':event,'at':time.time(),**fields}
        with open(STATE/'receipt.jsonl','a') as f:f.write(json.dumps(value)+'\n');f.flush();os.fsync(f.fileno())
        print(json.dumps(value),flush=True)
    stage_root=STATE/'stage-service';stage_link=PREFIX/'var/service'/STAGE_NAME
    old_run=(SERVICE/'run').read_text();old_pid=pid_for()
    if not identity(old_pid,'1.0.5'):raise RuntimeError('BASELINE_NOT_RUNNING')
    original_signature=signature(CANONICAL)
    if CANONICAL.is_symlink():raise RuntimeError('BASELINE_ROUTE_IS_ALREADY_MIGRATED')
    for p in [A,B,F,stage_link]:
        if p.exists() or p.is_symlink():raise RuntimeError('STAGING_PATH_ALREADY_EXISTS')
    release=json.loads((RUNTIME/'release-manifest.json').read_text())
    expected_files={'broker.mjs','main.mjs','persistent-bridge.mjs','bridge-config.json','bridge.dex','deploy.py','build-manifest.json'}
    if release.get('version')!=VERSION or set(release.get('files',{}))!=expected_files:
        raise RuntimeError('RELEASE_MANIFEST_MISMATCH')
    for name,digest in release['files'].items():
        if hashlib.sha256((RUNTIME/name).read_bytes()).hexdigest()!=digest:
            raise RuntimeError('RELEASE_FILE_DIGEST_MISMATCH')
    manifest=json.loads((RUNTIME/'build-manifest.json').read_text())
    if hashlib.sha256((RUNTIME/'bridge.dex').read_bytes()).hexdigest()!=manifest['dex_sha256']:
        raise RuntimeError('DEX_DIGEST_MISMATCH')
    if (RUNTIME/'bridge.dex').stat().st_mode&0o277:raise RuntimeError('DEX_NOT_PRIVATE_READ_ONLY')
    (STATE/'original-run').write_text(old_run)
    credential_file=HOME/'.selfrun-server/drive-auth-token.json'
    credential_before=signature(credential_file) if credential_file.exists() else None
    record('BASELINE_READY',pid=old_pid,google_drive_http=google_check(CANONICAL))
    monitor=Monitor();monitor.thread.start();routed=False;retired=False;run_changed=False;success=False
    def clean_stage():
        if stage_link.exists() or stage_link.is_symlink():
            try:stage_pid=pid_for(stage_link)
            except RuntimeError:stage_pid=None
            if stage_pid is not None:
                for _ in range(3):wait_for(lambda:drained_old(stage_pid),25);time.sleep(.1)
            sv('down',stage_link)
            stage_link.unlink()
            sv('exit',stage_root,check=False)
    try:
        stage_root.mkdir(mode=0o700)
        write_run(stage_root/'run',run_text(old_run,'1.0.5',A,True))
        os.symlink(stage_root,stage_link)
        wait_for(lambda:(stage_root/'supervise/control').exists(),20)
        sv('up',stage_link)
        wait_for(lambda:rpc(A),20)
        if not identity(pid_for(stage_link),'1.0.5'):raise RuntimeError('FALLBACK_VERSION_MISMATCH')
        record('PREVIOUS_RUNTIME_RELAY_READY',pid=pid_for(stage_link),google_drive_http=google_check(A))
        if signature(CANONICAL)!=original_signature or (SERVICE/'run').read_text()!=old_run:
            raise RuntimeError('CONCURRENT_BASELINE_CHANGE')
        atomic_route(CANONICAL,A,original_signature);routed=True
        record('ROUTED_TO_STAGE')
        for _ in range(3):wait_for(lambda:drained_old(old_pid),25);time.sleep(.1)
        if (SERVICE/'run').read_text()!=old_run or not identity(old_pid,'1.0.5'):
            raise RuntimeError('CONCURRENT_OLD_PROCESS_CHANGE')
        write_run(SERVICE/'run',run_text(old_run,VERSION,B));run_changed=True
        # Its old Node close handler owns the former public path and would unlink
        # the new route. Only terminate after every accepted old connection drained.
        os.kill(old_pid,signal.SIGKILL);retired=True
        record('OLD_DRAINED_AND_RETIRED',pid=old_pid)
        def stable_ready():
            pid=pid_for()
            return pid if pid!=old_pid and identity(pid,VERSION) and rpc(B,'health').get('runtime_version')==VERSION else False
        stable_pid=wait_for(stable_ready,25)
        record('STABLE_READY',pid=stable_pid,google_drive_http=google_check(B))
        atomic_route(CANONICAL,B,signature(CANONICAL))
        record('ROUTED_TO_STABLE')
        clean_stage()
        for _ in range(3):rpc(CANONICAL);time.sleep(.2)
        final_http=google_check(CANONICAL)
        health=rpc(CANONICAL,'health')
        credential_after=signature(credential_file) if credential_file.exists() else None
        if credential_before!=credential_after:raise RuntimeError('CREDENTIAL_METADATA_CHANGED')
        record('DEPLOYED',pid=stable_pid,google_drive_http=final_http,credential_metadata_unchanged=True,
               runtime=health,stage_removed=not stage_link.exists())
        success=True
    except Exception as error:
        record('TRANSACTION_FAILURE',error=type(error).__name__,reason=str(error) if isinstance(error,RuntimeError) else 'REDACTED',old_retired=retired)
        if not routed:
            if run_changed:write_run(SERVICE/'run',old_run)
            clean_stage()
            record('OLD_ROUTE_UNCHANGED',google_drive_http=google_check(CANONICAL))
        elif stage_link.exists():
            # The temporary relay deliberately runs the known-good previous code,
            # not the candidate. It remains supervised while restoring the main unit.
            google_check(A)
            if not CANONICAL.is_symlink() or os.readlink(CANONICAL)!=str(A):
                atomic_route(CANONICAL,A,signature(CANONICAL))
            current_pid=pid_for()
            if identity(current_pid,'1.0.5') and current_pid==old_pid:
                wait_for(lambda:drained_old(old_pid),25)
                write_run(SERVICE/'run',run_text(old_run,'1.0.5',F))
                os.kill(old_pid,signal.SIGKILL)
            else:
                if not identity(current_pid,VERSION):raise RuntimeError('ROLLBACK_PID_IDENTITY_CHANGED')
                for _ in range(3):
                    wait_for(lambda:connections_drained(current_pid,allow_children=True) and rpc(B,'health').get('in_flight',1)==0,25)
                    time.sleep(.1)
                sv('down',SERVICE)
                write_run(SERVICE/'run',run_text(old_run,'1.0.5',F))
                sv('up',SERVICE)
            wait_for(lambda:rpc(F),25);google_check(F)
            atomic_route(CANONICAL,F,signature(CANONICAL));clean_stage()
            record('ROLLED_BACK_SUPERVISED',google_drive_http=google_check(CANONICAL))
        else:
            record('WORKING_STABLE_ROUTE_RETAINED',google_drive_http=google_check(CANONICAL))
        raise
    finally:
        results=monitor.finish();record('CONTINUITY',**results)
        if success and results['failures']:raise RuntimeError('CONTINUITY_PROBE_FAILED')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    if not args.apply:parser.error('--apply is required')
    deploy()
