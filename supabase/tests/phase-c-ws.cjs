// Local Docker test driver. Receives keys/tokens via stdin only; never logs them.
const WS = require('/app/node_modules/ws');
const readline = require('readline');
const sockets = new Map();
const out = x => process.stdout.write(JSON.stringify(x) + '\n');
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function open(c) {
  const url = 'ws://kong:8000/realtime/v1/websocket?apikey=' + encodeURIComponent(c.key) + '&vsn=1.0.0';
  const ws = new WS(url, { handshakeTimeout: 10000 });
  const s = { ws, topic: 'realtime:' + c.topic, events: [], closed: false, channelClosed: false, pending: new Map(), ref: 2 };
  sockets.set(c.name, s);
  return await new Promise(resolve => {
    let done = false;
    const finish = status => { if (!done) { done = true; clearTimeout(timer); resolve({status}); } };
    const timer = setTimeout(() => { ws.terminate(); finish('timeout'); }, 12000);
    ws.on('open', () => ws.send(JSON.stringify({topic: s.topic, event: 'phx_join', ref: '1', join_ref: '1', payload: {
      config: { private: c.private !== false, broadcast: {ack: true, self: true}, presence: {enabled: false}, postgres_changes: [] }, access_token: c.token
    }})));
    ws.on('message', bytes => {
      let m; try { m = JSON.parse(bytes.toString()); } catch { return; }
      if (m.event === 'phx_reply' && m.ref === '1') finish(m.payload?.status || 'error');
      if (m.event === 'phx_error' || m.event === 'phx_close') {s.channelClosed=true;finish('error');}
      if (m.event === 'broadcast') s.events.push(m.payload);
      if (s.pending.has(m.ref)) { s.pending.get(m.ref)(m.payload?.status || 'error'); s.pending.delete(m.ref); }
    });
    ws.on('error', () => finish('error'));
    ws.on('close', () => { s.closed = true; finish('closed'); });
    s.heartbeat = setInterval(() => { if (ws.readyState === WS.OPEN) ws.send(JSON.stringify({topic:'phoenix',event:'heartbeat',payload:{},ref:String(s.ref++)})); }, 10000);
    s.heartbeat.unref();
  });
}
async function command(c) {
  if (c.op === 'open') return await open(c);
  if (c.op === 'quit') { for (const s of sockets.values()) {clearInterval(s.heartbeat);s.ws.terminate();} setTimeout(()=>process.exit(0),20); return {status:'ok'}; }
  const s = sockets.get(c.name); if (!s) return {status:'unknown'};
  if (c.op === 'drain') { const events=s.events.splice(0);return {status:'ok',closed:s.closed||s.channelClosed,events}; }
  if (c.op === 'close') {clearInterval(s.heartbeat);s.ws.terminate();return {status:'ok'};}
  if (c.op === 'refresh') {
    if (s.ws.readyState !== WS.OPEN) return {status:'closed'};
    s.ws.send(JSON.stringify({topic:s.topic,event:'access_token',payload:{access_token:c.token},ref:String(s.ref++),join_ref:'1'}));
    await sleep(150); return {status:s.closed||s.channelClosed?'closed':'sent'};
  }
  if (c.op === 'spoof') {
    if (s.ws.readyState !== WS.OPEN) return {status:'closed'};
    const ref=String(s.ref++);
    const ack = new Promise(resolve => {s.pending.set(ref,resolve);setTimeout(()=>{s.pending.delete(ref);resolve('timeout');},2000);});
    s.ws.send(JSON.stringify({topic:s.topic,event:'broadcast',payload:{type:'broadcast',event:'spoof',payload:{scope:'forbidden'}},ref,join_ref:'1'}));
    return {status:await ack};
  }
  return {status:'unsupported'};
}
let queue = Promise.resolve();
readline.createInterface({input:process.stdin}).on('line', line => {
  queue=queue.then(async()=>{let c;try{c=JSON.parse(line);out({id:c.id,...await command(c)});}catch{out({id:c?.id,status:'driver_error'});}});
}).on('close',()=>{for(const s of sockets.values())s.ws.terminate();process.exit(0);});
