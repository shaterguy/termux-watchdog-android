import test from 'node:test';
import assert from 'node:assert/strict';
import {WatchdogBroker} from '../broker.mjs';
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};}
function fixture(invoke){let now=0,calls=0;const broker=new WatchdogBroker({socketPath:'/unused',lockFile:'/unused',verify:async()=>{},
  now:()=>now,invoke:async(...args)=>{calls++;return invoke(...args);}});
  const send=req=>new Promise(resolve=>broker.reply(req,{destroyed:false,end:raw=>resolve(JSON.parse(raw))}));
  return {broker,send,setTime:t=>{now=t;},calls:()=>calls};}
const token=value=>({accessToken:value,expiryKnown:false});
test('access is reused for sixty seconds, then rechecks native state',async()=>{
  const f=fixture(()=>token('fixture-token'));assert.equal((await f.send({operation:'access'})).ok,true);
  f.setTime(59999);await f.send({operation:'access'});assert.equal(f.calls(),1);
  f.setTime(60000);await f.send({operation:'access'});assert.equal(f.calls(),2);await f.broker.close();
});
test('status retains its separate two-second cache',async()=>{
  const f=fixture(()=>({state:'ready',expiryKnown:false}));await f.send({operation:'status'});
  f.setTime(1999);await f.send({operation:'status'});assert.equal(f.calls(),1);
  f.setTime(2000);await f.send({operation:'status'});assert.equal(f.calls(),2);await f.broker.close();
});
test('health is diagnostic-only and never starts Android authentication',async()=>{
  const f=fixture(()=>{throw Error('must not invoke');});const r=await f.send({operation:'health'});
  assert.equal(r.diagnostic_only,true);assert.equal(r.runtime_version,'1.0.6-dev1');assert.equal(f.calls(),0);
  assert.equal('access_token' in r,false);assert.equal('state' in r,false);await f.broker.close();
});
test('concurrent access is single-flight',async()=>{
  const gate=deferred(),f=fixture(()=>gate.promise);
  const replies=Array.from({length:20},()=>f.send({operation:'access'}));await Promise.resolve();
  gate.resolve(token('fixture-single'));assert.equal((await Promise.all(replies)).every(x=>x.ok),true);
  assert.equal(f.calls(),1);await f.broker.close();
});
test('explicitly rejected token can never be reused',async()=>{
  const f=fixture(()=>token('fixture-rejected'));
  await f.send({operation:'access'});
  const r=await f.send({operation:'refresh',rejected_access_token:'fixture-rejected'});
  assert.equal(r.ok,false);assert.equal(f.calls(),2);await f.broker.close();
});
test('refresh takes precedence over access and replaces the cache',async()=>{
  const gate=deferred();const f=fixture(op=>op==='refresh'?gate.promise:token('fixture-old'));
  await f.send({operation:'access'});
  const refresh=f.send({operation:'refresh',rejected_access_token:'fixture-old'});
  const access=f.send({operation:'access'});gate.resolve(token('fixture-new'));
  assert.equal((await refresh).access_token,'fixture-new');assert.equal((await access).access_token,'fixture-new');
  assert.equal(f.calls(),2);await f.broker.close();
});
test('late old access cannot overwrite a newly refreshed token',async()=>{
  const accessGate=deferred(),refreshGate=deferred();const f=fixture(op=>op==='refresh'?refreshGate.promise:accessGate.promise);
  const first=f.send({operation:'access'});await Promise.resolve();
  const refreshed=f.send({operation:'refresh',rejected_access_token:'fixture-old'});
  refreshGate.resolve(token('fixture-new'));assert.equal((await refreshed).access_token,'fixture-new');
  accessGate.resolve(token('fixture-old'));assert.equal((await first).access_token,'fixture-new');
  assert.equal((await f.send({operation:'access'})).access_token,'fixture-new');await f.broker.close();
});
test('not-ready status invalidates cached access',async()=>{
  const f=fixture(op=>op==='status'?{state:'reauth_required'}:token('fixture-ok'));
  await f.send({operation:'access'});await f.send({operation:'status'});await f.send({operation:'access'});
  assert.equal(f.calls(),3);await f.broker.close();
});
test('temporary failures retain exponential backoff rather than spawning repeatedly',async()=>{
  const f=fixture(()=>{throw Object.assign(Error(),{code:'AUTH_TIMEOUT'});});
  assert.equal((await f.send({operation:'access'})).error_code,'AUTH_TEMPORARY_UNAVAILABLE');
  f.setTime(999);await f.send({operation:'access'});assert.equal(f.calls(),1);
  f.setTime(1000);await f.send({operation:'access'});assert.equal(f.calls(),2);
  f.setTime(2999);await f.send({operation:'access'});assert.equal(f.calls(),2);
  f.setTime(3000);await f.send({operation:'access'});assert.equal(f.calls(),3);await f.broker.close();
});
test('malformed fields and credential-bearing health requests fail closed',async()=>{
  const f=fixture(()=>token('fixture-ok'));
  for(const req of [{operation:'bogus'},{operation:'health',rejected_access_token:'fixture'},
    {operation:'access',unexpected:true},{operation:'refresh',rejected_access_token:'bad token'}]) {
    assert.equal((await f.send(req)).ok,false);
  }assert.equal(f.calls(),0);await f.broker.close();
});
