import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {PassThrough,Writable} from 'node:stream';
import {setTimeout as delay} from 'node:timers/promises';
import {PersistentBridgePool} from '../persistent-bridge.mjs';
function setup({mode='ok',idleMs=300000,requestTimeoutMs=1000}={}) {
  const children=[];
  const createChild=()=>{
    const child=new EventEmitter();child.stdout=new PassThrough();child.killed=false;
    child.kill=()=>{child.killed=true;queueMicrotask(()=>child.emit('close',null));return true;};
    child.stdin=new Writable({write(data,enc,callback){
      callback();
      queueMicrotask(()=>{
        if(mode==='hang')return;
        if(mode==='exit'){child.emit('close',2);return;}
        if(mode==='oversized'){child.stdout.write('x'.repeat(16385));return;}
        if(mode==='extra'){child.stdout.write('{}\n{}\n');return;}
        if(mode==='bad'){child.stdout.write('not-json\n');return;}
        const lines=data.toString().trim().split('\n');assert.equal(lines[0],'B1');
        child.stdout.write(JSON.stringify({ok:true,op:JSON.parse(lines[1]).op})+'\n');
      });
    }});
    children.push(child);return child;
  };
  const pool=new PersistentBridgePool({createChild,decode:raw=>JSON.parse(raw),idleMs,requestTimeoutMs});
  return {pool,children,setMode:value=>{mode=value;}};
}
test('one process handles sequential status and access frames',async()=>{
  const {pool}=setup();try {
    assert.equal((await pool.request({op:'status'},'status')).op,'status');
    assert.equal((await pool.request({op:'access'},'access')).op,'access');
    assert.deepEqual(pool.stats,{requests:2,spawned:1,completed:2,failed:0,timeouts:0,alive:1,busy:0});
  }finally{pool.close();}
});
test('maximum four native processes; fifth request is rejected without replay',async()=>{
  const {pool}=setup({mode:'hang'});
  const pending=Array.from({length:4},()=>pool.request({op:'access'},'access').catch(e=>e.code));
  await assert.rejects(pool.request({op:'status'},'status'),{code:'AUTH_UNAVAILABLE'});
  assert.equal(pool.stats.spawned,4);pool.close();await Promise.all(pending);
});
for(const mode of ['extra','bad','oversized','exit'])test(`${mode} response retires only the bad child`,async()=>{
  const {pool,children,setMode}=setup({mode});
  try{await assert.rejects(pool.request({op:'status'},'status'),{code:'AUTH_UNAVAILABLE'});
    assert.equal(children[0].killed,true);assert.equal(pool.stats.alive,0);
    setMode('ok');assert.equal((await pool.request({op:'status'},'status')).ok,true);assert.equal(pool.stats.spawned,2);
  }finally{pool.close();}
});
test('timeout is bounded and a refresh is never replayed',async()=>{
  const {pool}=setup({mode:'hang',requestTimeoutMs:20});
  try{await assert.rejects(pool.request({op:'refresh',rejectedAccessToken:'fixture'},'refresh'),{code:'AUTH_TIMEOUT'});
    assert.equal(pool.stats.spawned,1);assert.equal(pool.stats.requests,1);assert.equal(pool.stats.alive,0);
  }finally{pool.close();}
});
test('idle timer removes children without starting a heartbeat',async()=>{
  const {pool,children}=setup({idleMs:20});
  try{await pool.request({op:'status'},'status');await delay(60);
    assert.equal(children[0].killed,true);assert.equal(pool.stats.alive,0);assert.equal(pool.stats.requests,1);
  }finally{pool.close();}
});
test('close rejects outstanding work once and never allows new work',async()=>{
  const {pool}=setup({mode:'hang'});const pending=pool.request({op:'access'},'access');
  pool.close();pool.close();await assert.rejects(pending,{code:'AUTH_UNAVAILABLE'});
  await assert.rejects(pool.request({op:'access'},'access'),{code:'AUTH_UNAVAILABLE'});
  assert.equal(pool.stats.failed,1);assert.equal(pool.stats.alive,0);
});
