// A process is reused; every exchange still establishes a fresh verified Binder session.
const failure=code=>Object.assign(new Error(code),{code});
export class PersistentBridgePool {
  #workers=new Set();
  #closed=false;
  constructor({createChild,decode,maxWorkers=4,idleMs=300000,requestTimeoutMs=9500}) {
    Object.assign(this,{createChild,decode,maxWorkers,idleMs,requestTimeoutMs});
    this.counts={requests:0,spawned:0,completed:0,failed:0,timeouts:0};
  }
  get stats() {
    return {...this.counts,alive:this.#workers.size,busy:[...this.#workers].filter(w=>w.pending).length};
  }
  request(frame,op) {
    if(this.#closed)return Promise.reject(failure('AUTH_UNAVAILABLE'));
    this.counts.requests++;
    let worker=[...this.#workers].find(w=>!w.pending);
    if(!worker) {
      if(this.#workers.size>=this.maxWorkers)return Promise.reject(failure('AUTH_UNAVAILABLE'));
      try {worker=this.#create();} catch {return Promise.reject(failure('AUTH_UNAVAILABLE'));}
    }
    clearTimeout(worker.idle);
    return new Promise((resolve,reject)=>{
      worker.pending={resolve,reject,op,timer:setTimeout(()=>{
        this.counts.timeouts++;
        this.#retire(worker,failure('AUTH_TIMEOUT'));
      },this.requestTimeoutMs)};
      try {
        // The non-secret marker wakes an idle process. Credential bytes are consumed
        // by BridgeCli only after the fresh mutual identity handshake succeeds.
        worker.child.stdin.write('B1\n'+JSON.stringify(frame)+'\n',error=>{
          if(error)this.#retire(worker,failure('AUTH_UNAVAILABLE'));
        });
      } catch {this.#retire(worker,failure('AUTH_UNAVAILABLE'));}
    });
  }
  #create() {
    const child=this.createChild();
    const worker={child,pending:null,idle:null,buffer:Buffer.alloc(0),retired:false};
    this.#workers.add(worker);this.counts.spawned++;
    child.on('error',()=>this.#retire(worker,failure('AUTH_UNAVAILABLE')));
    child.on('close',code=>this.#retire(worker,failure(code===124?'AUTH_TIMEOUT':'AUTH_UNAVAILABLE')));
    child.stdin.on('error',()=>this.#retire(worker,failure('AUTH_UNAVAILABLE')));
    child.stdout.on('error',()=>this.#retire(worker,failure('AUTH_UNAVAILABLE')));
    child.stdout.on('data',chunk=>this.#receive(worker,chunk));
    return worker;
  }
  #receive(worker,chunk) {
    if(worker.retired)return;
    if(!worker.pending || worker.buffer.length+chunk.length>16384 || chunk.some(byte=>byte>127)) {
      this.#retire(worker,failure('AUTH_UNAVAILABLE'));return;
    }
    worker.buffer=Buffer.concat([worker.buffer,chunk]);
    const end=worker.buffer.indexOf(10);
    if(end<0)return;
    if(end!==worker.buffer.length-1) {this.#retire(worker,failure('AUTH_UNAVAILABLE'));return;}
    let value;
    try {value=this.decode(worker.buffer.toString('ascii'),worker.pending.op);}
    catch(error) {this.#retire(worker,error?.code?error:failure('AUTH_UNAVAILABLE'));return;}
    const pending=worker.pending;worker.pending=null;worker.buffer=Buffer.alloc(0);
    clearTimeout(pending.timer);this.counts.completed++;
    worker.idle=setTimeout(()=>this.#retire(worker),this.idleMs);
    worker.idle.unref?.();
    pending.resolve(value);
  }
  #retire(worker,error=failure('AUTH_UNAVAILABLE')) {
    if(worker.retired)return;
    worker.retired=true;this.#workers.delete(worker);clearTimeout(worker.idle);
    const pending=worker.pending;worker.pending=null;worker.buffer=Buffer.alloc(0);
    if(pending) {
      clearTimeout(pending.timer);this.counts.failed++;pending.reject(error);
    }
    worker.child.stdin.destroy();worker.child.stdout.destroy();
    // No automatic replay: a refresh may already have invalidated the rejected token.
    try {worker.child.kill('SIGKILL');} catch {}
  }
  close() {
    this.#closed=true;
    for(const worker of [...this.#workers])this.#retire(worker);
  }
}
