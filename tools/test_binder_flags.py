"""Compile the exact guard expressions from both production Binder entrypoints."""
import pathlib,re,shutil,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
J=ROOT/'app/src/main/java/com/shaterguy/termuxwatchdog'
class BinderFlags(unittest.TestCase):
 def test_actual_admission_guards_with_native_transport_flags(self):
  cli=(J/'BridgeCli.java').read_text();receiver=(J/'NativeAuthReceiver.java').read_text()
  callback=re.search(r'if\(([^\n]+)\) return false;',cli[cli.index('int peerUid=Binder.getCallingUid();'):]).group(1)
  endpoint=re.search(r'if\(([^\n]+)\) return false;',receiver[receiver.index('int peerUid=Binder.getCallingUid();'):]).group(1)
  source='''import java.util.concurrent.atomic.AtomicBoolean;
public final class ActualBinderGuards {
 static int checks; static void check(boolean ok){checks++;if(!ok)throw new AssertionError("guard check "+checks);}
 static final class IBinder {static final int FLAG_ONEWAY=1;}
 static final class BridgeHandshake {static final int MAX_PARCEL_BYTES=1024;}
 static final class Parcel {int bytes;Parcel(int b){bytes=b;}int dataSize(){return bytes;}}
 static final class Peers {boolean verified=true;boolean verify(int uid){return verified&&uid==10031;}boolean verifyWatchdog(int uid){return verified&&uid==10069;}}
 static final Peers peers=new Peers();static AtomicBoolean finished=new AtomicBoolean();static int sentFromUid=10031,max=1024;
 static boolean callback(int flags,int peerUid,Parcel data){return !(CALLBACK);}
 static boolean endpoint(int flags,int peerUid,Parcel data,Parcel reply){return !(ENDPOINT);}
 public static void main(String[] args){
  Parcel data=new Parcel(280),reply=new Parcel(0);
  for(int flag:new int[]{1,17,3,19,33,49})check(callback(flag,10069,data));
  for(int flag:new int[]{0,16,2,18,32,48})check(!callback(flag,10069,data));
  for(int flag:new int[]{0,16,2,18,32,48})check(endpoint(flag,10031,data,reply));
  for(int flag:new int[]{1,17,3,19,33,49})check(!endpoint(flag,10031,data,reply));
  for(int flag:new int[]{1,17}){check(!callback(flag,10070,data));peers.verified=false;check(!callback(flag,10069,data));peers.verified=true;check(!callback(flag,10069,new Parcel(1025)));}
  for(int flag:new int[]{0,16}){check(!endpoint(flag,10032,data,reply));peers.verified=false;check(!endpoint(flag,10031,data,reply));peers.verified=true;check(!endpoint(flag,10031,new Parcel(1025),reply));check(!endpoint(flag,10031,data,null));finished.set(true);check(!endpoint(flag,10031,data,reply));finished.set(false);sentFromUid=10032;check(!endpoint(flag,10031,data,reply));sentFromUid=10031;}
  check(callback(17,10069,new Parcel(1024)));check(endpoint(16,10031,new Parcel(1024),reply));
  System.out.println("ACTUAL_BINDER_GUARDS_PASS checks="+checks);
 }
}'''.replace('CALLBACK',callback).replace('ENDPOINT',endpoint)
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'ActualBinderGuards.java';p.write_text(source);java=shutil.which('java') or '/usr/lib/jvm/default-java/bin/java'
   subprocess.run([java,'com.sun.tools.javac.Main','-d',d,str(p)],capture_output=True,check=True)
   result=subprocess.run([java,'-cp',d,'ActualBinderGuards'],capture_output=True,text=True)
   self.assertEqual(result.returncode,0,result.stderr);self.assertIn('ACTUAL_BINDER_GUARDS_PASS checks=44',result.stdout)
if __name__=='__main__':unittest.main()
