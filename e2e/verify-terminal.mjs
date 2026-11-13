// Opens a terminal on a harness node, types commands, resizes, reattaches. Usage: node verify-terminal.mjs <base> <token> <node>
const [base, token, node] = process.argv.slice(2);
const auth = { Authorization: `Bearer ${token}` };
const api = async (method, path) => {
  const r = await fetch(base + path, { method, headers: auth });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status} ${text}`);
  return JSON.parse(text);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function attach(id, origin) {
  const { ticket } = await api('POST', `/api/terminal/${id}/ticket`);
  const ws = new WebSocket(base.replace('http', 'ws') + `/api/terminal/ws?ticket=${ticket}`, { headers: { Origin: origin ?? base } });
  ws.binaryType = 'arraybuffer';
  const state = { ws, out: '', control: [], offset: 0, closed: null };
  ws.onmessage = (e) => {
    if (typeof e.data === 'string') {
      const m = JSON.parse(e.data);
      state.control.push(m);
      if (m.type === 'hello') state.offset = m.offset;
    } else {
      state.out += Buffer.from(e.data).toString('utf8');
      state.offset += e.data.byteLength;
      ws.send(JSON.stringify({ type: 'ack', offset: state.offset }));
    }
  };
  ws.onclose = (e) => { state.closed = `${e.code} ${e.reason}`; };
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('socket error')); });
  return state;
}
const type = (s, text) => s.ws.send(new TextEncoder().encode(text));
const waitFor = async (s, text, ms = 8000) => {
  const end = Date.now() + ms;
  while (!s.out.includes(text) && Date.now() < end) await sleep(50);
  return s.out.includes(text);
};

const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`); };

const session = await api('POST', `/api/nodes/${node}/terminal?rows=30&columns=100`);
let s = await attach(session.id);
type(s, 'stty size; pwd; echo marker-$((40+2))\n');
check('the shell runs in a 30x100 terminal', await waitFor(s, '30 100'));
check('a command gives output', await waitFor(s, 'marker-42'));
check('the shell starts in the install directory', s.out.includes('/opt/homegap'), JSON.stringify(s.out.slice(-200)));
s.ws.send(JSON.stringify({ type: 'resize', rows: 44, columns: 132 }));
await sleep(300);
type(s, 'stty size\n');
check('a resize reaches the shell', await waitFor(s, '44 132'));

// A second ticket is needed: the first one worked one time.
s.ws.close();
await sleep(500);
const overview = await api('GET', '/api/terminal');
check('the session stays when the browser goes', overview.sessions.some((x) => x.id === session.id && !x.attached));
let s2 = await attach(session.id);
check('a new connection shows the earlier output', await waitFor(s2, 'marker-42', 3000));

// A flood: the output must arrive whole and in order.
type(s2, 'seq 1 200000; echo flood-$((1+1))\n');
check('a large output ends', await waitFor(s2, 'flood-2', 60000));
const lines = s2.out.split('\r\n');
// The line "1" has a control sequence of the shell before it, so the count starts at "2".
const first = lines.indexOf('2');
let whole = first >= 0;
for (let i = 0; whole && i < 199999; i++) whole = lines[first + i] === String(i + 2);
check('the large output is whole and in order', whole, `${lines.length} lines`);

// A page of another site cannot attach.
let refused = false;
try { const bad = await attach(session.id, 'http://evil.example'); await sleep(500); refused = bad.closed !== null; } catch { refused = true; }
check('a page of another origin is refused', refused);

type(s2, 'exit 7\n');
const end = Date.now() + 8000;
while (!s2.control.some((m) => m.type === 'exit') && Date.now() < end) await sleep(50);
check('the exit code arrives', s2.control.some((m) => m.type === 'exit' && m.code === 7), JSON.stringify(s2.control));
await api('DELETE', `/api/terminal/${session.id}`);
check('the session is gone after a close', !(await api('GET', '/api/terminal')).sessions.some((x) => x.id === session.id));
process.exit(results.every(Boolean) ? 0 : 1);
