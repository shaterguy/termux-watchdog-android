import { createInterface } from 'node:readline';
const persistent=process.argv.includes('--persistent');
const rl=createInterface({input:process.stdin,crlfDelay:Infinity});
rl.on('line',line=>{
  if(line==='B1') return;
  let req;
  try {req=JSON.parse(line);} catch {process.exit(2);return;}
  const response=req.op==='status'
    ? {version:1,ok:true,state:'ready',expiryKnown:false}
    : {version:1,ok:true,accessToken:'fixture-access',expiryKnown:false};
  process.stdout.write(JSON.stringify(response)+'\n',()=>{if(!persistent)process.exit(0);});
});
