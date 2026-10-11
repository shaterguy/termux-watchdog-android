import fs from 'node:fs/promises';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { PersistentBridgePool } from './persistent-bridge.mjs';
import { createHash } from 'node:crypto';
import { acquireLock } from '/data/data/com.termux/files/home/.local/lib/termux-google-auth/store.mjs';

const configFile = new URL('./bridge-config.json', import.meta.url);
const configStat = await fs.lstat(configFile);
if (!configStat.isFile() || configStat.isSymbolicLink() || configStat.uid!==process.getuid() ||
    (configStat.mode & 0o077)!==0 || configStat.size>1024) throw Error('AUTH_NOT_READY');
const config = JSON.parse(await fs.readFile(configFile,'utf8'));
if (!config || typeof config!=='object' || Array.isArray(config) || Object.keys(config).length!==1 ||
    !['/data/data/com.termux/files/home/.local/lib/termux-google-auth/watchdog-v1.0.6-dev1/bridge.dex'].includes(config.bridgePath))
  throw Error('AUTH_NOT_READY');
const BRIDGE = config.bridgePath;
const BRIDGE_SHA256 = '9f3007571343e5be09c530a5503a9e7fc9692877c130285928bcb50edf245079';
const DALVIK = '/system/bin/app_process';
const ENTRY = 'com.shaterguy.termuxwatchdog.BridgeCli';
const SAFE_STATE = new Set(['ready','reauth_required','not_ready','unavailable','not_configured']);
const TOKEN = /^[A-Za-z0-9._~+/-]+=*$/;
const CODE_MAP = {
  AUTH_NOT_READY:'AUTH_NOT_READY',
  AUTH_REAUTH_REQUIRED:'AUTH_REAUTH_REQUIRED',
  AUTH_NOT_CONFIGURED:'AUTH_NOT_READY',
  AUTH_UNAVAILABLE:'AUTH_SERVICE_UNAVAILABLE',
  AUTH_TIMEOUT:'AUTH_TEMPORARY_UNAVAILABLE',
  PEER_DENIED:'AUTH_NOT_READY',
  BAD_REQUEST:'AUTH_INTERNAL_ERROR',
};
const fail = (code) => Object.assign(new Error(code), {code});
function mapped(code) { return CODE_MAP[code] || 'AUTH_INTERNAL_ERROR'; }

async function verifyBridge() {
  const st = await fs.lstat(BRIDGE);
  if (!st.isFile() || st.isSymbolicLink() || st.uid !== process.getuid() ||
      (st.mode & 0o277) !== 0 || st.size > 500000) throw fail('AUTH_NOT_READY');
  const digest = createHash('sha256').update(await fs.readFile(BRIDGE)).digest('hex');
  if (digest !== BRIDGE_SHA256) throw fail('AUTH_NOT_READY');
}

function decodeNative(raw,op) {
  let value;
  try {
    value=JSON.parse(raw);
    if(!value || Array.isArray(value) || value.version!==1 || typeof value.ok!=='boolean') throw Error();
    if(!value.ok) {
      if(!value.error || typeof value.error.code!=='string') throw Error();
    } else if(op==='status') {
      if(!SAFE_STATE.has(value.state) || value.expiryKnown!==false || Object.hasOwn(value,'accessToken')) throw Error();
    } else if(typeof value.accessToken!=='string' || value.accessToken.length>8192 ||
              !TOKEN.test(value.accessToken) || value.expiryKnown!==false) throw Error();
  } catch {throw fail('AUTH_UNAVAILABLE');}
  if(!value.ok)throw fail(value.error.code);
  return value;
}
const nativePool=new PersistentBridgePool({
  createChild:()=>spawn(DALVIK,['/',ENTRY,'--persistent'],{
    stdio:['pipe','pipe','ignore'],
    env: {
        ANDROID_DATA:'/data/data/com.termux/files/usr/var/android',
        CLASSPATH:BRIDGE,
        ANDROID_ART_ROOT:process.env.ANDROID_ART_ROOT,
        ANDROID_I18N_ROOT:process.env.ANDROID_I18N_ROOT,
        ANDROID_TZDATA_ROOT:process.env.ANDROID_TZDATA_ROOT,
        BOOTCLASSPATH:process.env.BOOTCLASSPATH,
        DEX2OATBOOTCLASSPATH:process.env.DEX2OATBOOTCLASSPATH,
        HOME:'/data/data/com.termux/files/home',
        PREFIX:'/data/data/com.termux/files/usr',
        PATH:'/data/data/com.termux/files/usr/bin:/system/bin',
        TMPDIR:'/data/data/com.termux/files/usr/tmp'
      }
  }),
  decode:decodeNative
});
export function nativeBridge(op,rejectedAccessToken) {
  const frame={version:1,op,...(op==='refresh'?{rejectedAccessToken}:{})};
  return nativePool.request(frame,op);
}
export function closeNativeBridge() {nativePool.close();}
export function nativeBridgeStats() {return nativePool.stats;}

export class WatchdogBroker {
  #flights = new Map();
  #failures = new Map();
  #status = null;
  #statusEpoch = 0;
  #closing = null;
  #access = null;
  #authEpoch = 0;
  #refresh = null;
  #rejected = new Set();
  constructor({socketPath,lockFile,invoke=nativeBridge,verify=verifyBridge,now=()=>performance.now()}={}) {
    if (!socketPath || !lockFile) throw Error('BROKER_CONFIG_REQUIRED');
    Object.assign(this,{socketPath,lockFile,invoke,verify,now});
    this.closed = false;
    this.server = null;
    this.inFlight = 0;
    this.waiters = 0;
  }
  async start() {
    await this.verify();
    this.releaseLock = await acquireLock(this.lockFile, 500);
    try {
      await fs.rm(this.socketPath,{force:true});
      this.server = net.createServer(s => this.onConnection(s));
      await new Promise((resolve,reject) => {
        this.server.once('error',reject);
        this.server.listen(this.socketPath,resolve);
      });
      await fs.chmod(this.socketPath,0o600);
    } catch (error) {
      await this.close();
      throw error;
    }
  }
  onConnection(socket) {
    if (this.closed) {socket.destroy(); return;}
    let bytes=0, raw='', started=false;
    socket.on('error',()=>{});
    socket.setTimeout(20000,()=>socket.destroy());
    socket.on('data', chunk => {
      if (started) return;
      bytes += chunk.length;
      if (bytes > 16384) { started=true; socket.destroy(); return; }
      raw += chunk.toString('utf8');
      const end = raw.indexOf('\n');
      if (end === -1) return;
      started = true;
      const payload = raw.slice(0,end);
      if (raw.slice(end+1).length || !payload || this.closed) {
        socket.end(JSON.stringify({ok:false,error_code:'AUTH_INTERNAL_ERROR'})+'\n');
        return;
      }
      let req;
      try {req=JSON.parse(payload);} catch {
        socket.end(JSON.stringify({ok:false,error_code:'AUTH_INTERNAL_ERROR'})+'\n');
        return;
      }
      this.reply(req,socket).catch(()=>{
        if (!socket.destroyed) socket.end(JSON.stringify({ok:false,error_code:'AUTH_INTERNAL_ERROR'})+'\n');
      });
    });
  }
  async reply(req,socket) {
    if (!req || typeof req !== 'object' || Array.isArray(req) ||
        !['status','access','refresh','health'].includes(req.operation) ||
        Object.keys(req).some(k=>k!=='operation'&&k!=='rejected_access_token') ||
        (req.operation==='refresh' && req.rejected_access_token!==undefined &&
          (typeof req.rejected_access_token!=='string' ||
           req.rejected_access_token.length>8192 || !TOKEN.test(req.rejected_access_token)))) {
      socket.end(JSON.stringify({ok:false,error_code:'AUTH_INTERNAL_ERROR'})+'\n');
      return;
    }
    if(req.operation==='health') {
      if(Object.keys(req).length!==1) {
        socket.end(JSON.stringify({ok:false,error_code:'AUTH_INTERNAL_ERROR'})+'\n');return;
      }
      socket.end(JSON.stringify({ok:true,service:'termux-google-auth',runtime_version:'1.0.6-dev1',
        diagnostic_only:true,in_flight:this.inFlight,waiters:this.waiters,native:nativeBridgeStats()})+'\n');return;
    }
    if (req.operation==='refresh' && req.rejected_access_token!==undefined) this.#reject(req.rejected_access_token);
    if (this.waiters>=128) {
      socket.end(JSON.stringify({ok:false,error_code:'AUTH_TEMPORARY_UNAVAILABLE'})+'\n');
      return;
    }
    this.waiters++;
    try {
      let reply=await this.request(req);
      // A shared result must still satisfy this individual refresh caller.
      if (req.operation==='refresh' && reply.ok && reply.access_token===req.rejected_access_token) {
        this.#reject(req.rejected_access_token);
        reply={ok:false,error_code:'AUTH_SERVICE_UNAVAILABLE'};
      }
      if (!socket.destroyed) socket.end(JSON.stringify(reply)+'\n');
    } finally {this.waiters--;}

  }
  #reject(token) {
    const key=createHash('sha256').update(token).digest('hex');
    this.#rejected.delete(key);this.#rejected.add(key);
    if (this.#access?.reply.access_token===token) {
      this.#access=null;this.#authEpoch++;this.#status=null;this.#statusEpoch++;
    }
    if (this.#rejected.size>128) this.#rejected.delete(this.#rejected.values().next().value);
  }
  async request(req) {
    if (this.closed) return {ok:false,error_code:'AUTH_SERVICE_UNAVAILABLE'};
    const op=req.operation;
    // Refresh failures and flights belong to the exact rejected token, never a different one.
    const key=op==='refresh' ? 'refresh:'+(req.rejected_access_token===undefined ? 'none' :
      createHash('sha256').update(req.rejected_access_token).digest('hex')) : op;
    const active=this.#flights.get(key);
    if (active) return active;
    const now=this.now();
    if (op==='access' && this.#refresh) return this.#refresh;
    // This is an account-state recheck interval, not a claim about token expiry.
    if ((op==='access' || (op==='refresh' && req.rejected_access_token!==undefined &&
        this.#access?.reply.access_token!==req.rejected_access_token)) &&
        this.#access && now<this.#access.until) return this.#access.reply;
    if (op==='status' && this.#status && now<this.#status.until) return this.#status.reply;
    const failed=this.#failures.get(key);
    if (failed && now<failed.until) return failed.reply;
    if (this.inFlight>=4) return {ok:false,error_code:'AUTH_TEMPORARY_UNAVAILABLE'};
    if (op==='refresh') {this.#status=null;this.#statusEpoch++;this.#access=null;this.#authEpoch++;}
    if (op==='access') this.#access=null;
    const epoch=this.#statusEpoch, authEpoch=this.#authEpoch;
    this.inFlight++;
    const work=this.invokeReply(req).then(async reply=>{
      if (op!=='status' && authEpoch!==this.#authEpoch) {
        if (this.#access && this.now()<this.#access.until &&
            this.#access.reply.access_token!==req.rejected_access_token) return this.#access.reply;
        if (this.#refresh && this.#refresh!==work) return this.#refresh;
        return {ok:false,error_code:'AUTH_TEMPORARY_UNAVAILABLE'};
      }
      if (op==='status' && authEpoch===this.#authEpoch && (!reply.ok || reply.state!=='ready')) {
        this.#access=null;this.#authEpoch++;
      }
      if (op==='refresh') {this.#status=null;this.#statusEpoch++;}
      if (reply.ok) {
        this.#failures.delete(key);
        if (op!=='status') this.#access={reply,until:this.now()+60000};
        if (op==='refresh') this.#failures.delete('access');
        if (op==='status' && epoch===this.#statusEpoch) this.#status={reply,until:this.now()+2000};
      } else {
        const attempt=failed && now-failed.at<60000 ? Math.min(failed.attempt+1,4) : 1;
        this.#failures.delete(key);
        this.#failures.set(key,{reply,attempt,at:this.now(),until:this.now()+1000*2**(attempt-1)});
        if (this.#failures.size>128) this.#failures.delete(this.#failures.keys().next().value);
      }
      return reply;
    }).finally(()=>{
      this.#flights.delete(key);this.inFlight--;
      if (this.#refresh===work) this.#refresh=null;
    });
    this.#flights.set(key,work);
    if (op==='refresh') this.#refresh=work;
    return work;
  }
  async invokeReply(req) {
    let reply;
    try {
      await this.verify();
      const op=req.operation;
      const result=await this.invoke(op,op==='refresh'?req.rejected_access_token:undefined);
      if (op==='status') {
        if (!SAFE_STATE.has(result.state) || Object.hasOwn(result,'accessToken')) throw fail('AUTH_UNAVAILABLE');
        reply={ok:true,service:'termux-google-auth',state:result.state,auth_owner:'android',expiry_known:false};
      } else {
        if (typeof result.accessToken!=='string' || result.accessToken.length>8192 ||
            !TOKEN.test(result.accessToken)) throw fail('AUTH_UNAVAILABLE');
        if (this.#rejected.has(createHash('sha256').update(result.accessToken).digest('hex'))) throw fail('AUTH_UNAVAILABLE');
        reply={ok:true,access_token:result.accessToken,expiry_known:false};
      }
    } catch(error) {
      reply={ok:false,error_code:mapped(error?.code)};
    }
    return reply;
  }
  close() {
    if (this.#closing) return this.#closing;
    this.closed=true;
    this.#closing=(async()=>{
      if (this.server) {
        const srv=this.server;
        this.server=null;
        // Node owns removal of its listening socket. Never unlink the path again
        // after drain: it may already belong to a replacement server.
        await new Promise(resolve=>srv.close(resolve));
      }
      await Promise.all(this.#flights.values());
      if(this.invoke===nativeBridge)closeNativeBridge();
      this.#status=null;
      this.#access=null;
      this.#refresh=null;
      this.#failures.clear();
      this.#rejected.clear();
      if (this.releaseLock) {
        const release=this.releaseLock;this.releaseLock=null;
        await release();
      }
    })();
    return this.#closing;
  }
}
