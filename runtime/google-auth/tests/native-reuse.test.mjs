import test from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { fileURLToPath } from 'node:url';

test('sequential native requests reuse one Android runtime process',async()=>{
  const realSpawn=childProcess.spawn,children=[];
  let starts=0,mod;
  childProcess.spawn=(command,args,options)=>{
    if(command!=='/system/bin/app_process')return realSpawn(command,args,options);
    starts++;
    const child=realSpawn(process.execPath,[fileURLToPath(new URL('./fake-runtime.mjs',import.meta.url)),...args.filter(x=>x==='--persistent')],{stdio:['pipe','pipe','ignore']});
    children.push(child);return child;
  };
  syncBuiltinESMExports();
  try {
    mod=await import('../broker.mjs?reuse-regression');
    assert.equal((await mod.nativeBridge('status')).state,'ready');
    assert.equal((await mod.nativeBridge('status')).state,'ready');
    assert.equal(starts,1,'two requests must not start two Android runtimes');
  } finally {
    if(mod?.closeNativeBridge)await mod.closeNativeBridge();
    for(const child of children)if(child.exitCode===null)child.kill('SIGKILL');
    childProcess.spawn=realSpawn;syncBuiltinESMExports();
  }
});
