import os from 'node:os';
import path from 'node:path';
import { WatchdogBroker } from './broker.mjs';
const tokenFile=process.env.TERMUX_GOOGLE_AUTH_TOKEN_FILE ||
  path.join(os.homedir(),'.selfrun-server','drive-auth-token.json');
const socketPath=process.env.TERMUX_GOOGLE_AUTH_SOCKET || tokenFile+'.service.sock';
const broker=new WatchdogBroker({socketPath,lockFile:tokenFile+'.service.lock'});
let ending=false;
async function shutdown() {
  if (ending) return;
  ending=true;
  await broker.close();
}
process.on('SIGTERM',()=>shutdown().catch(()=>{process.exitCode=1}));
process.on('SIGINT',()=>shutdown().catch(()=>{process.exitCode=1}));
try {await broker.start();}
catch {console.error('WATCHDOG_BROKER_START_FAILED');process.exitCode=1;}
