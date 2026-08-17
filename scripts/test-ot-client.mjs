/**
 * Convergence test for the browser OT client (src/main/resources/static/ot.js).
 *
 * Simulates the server's canonical operation log (same transform rules as
 * OperationalTransformService) and N clients editing concurrently with
 * arbitrary message interleaving, then asserts every client converges to the
 * server document.
 *
 * Run: node scripts/test-ot-client.mjs
 */
import { transformOp, applyOp, rebase } from '../src/main/resources/static/ot.js';

class Server {
  constructor() {
    this.doc = '';
    this.log = []; // canonical ops in revision order (rev = index + 1)
  }
  // Client submits op composed against baseRevision; transform against
  // every canonical op after it (mirrors the server-side pipeline).
  submit(op, baseRevision) {
    let t = { ...op };
    for (let i = baseRevision; i < this.log.length; i++) {
      t = transformOp(t, this.log[i]);
    }
    this.doc = applyOp(this.doc, t);
    this.log.push(t);
    return { op: t, revision: this.log.length };
  }
}

class Client {
  constructor(id, server) {
    this.id = id;
    this.server = server;
    this.doc = '';
    this.revision = 0;
    this.inflight = null;
    this.outbox = [];
    this.inbox = []; // canonical broadcasts not yet processed
  }
  typeInsert(pos, text) {
    const op = { operationType: 'INSERT', position: Math.min(pos, this.doc.length), text, author: this.id };
    this.doc = applyOp(this.doc, op);
    this.outbox.push(op);
  }
  typeDelete(pos, len) {
    if (!this.doc.length) return;
    const p = Math.min(pos, this.doc.length - 1);
    const l = Math.min(len, this.doc.length - p);
    const op = { operationType: 'DELETE', position: p, length: l, author: this.id };
    this.doc = applyOp(this.doc, op);
    this.outbox.push(op);
  }
  maybeSend() {
    if (this.inflight || !this.outbox.length) return null;
    this.inflight = this.outbox.shift();
    return { op: { ...this.inflight }, baseRevision: this.revision, from: this.id };
  }
  receive(broadcast) {
    // broadcast: { op, revision, from }
    this.revision = Math.max(this.revision, broadcast.revision);
    if (broadcast.from === this.id) {
      this.inflight = null; // ack
      return;
    }
    // Same rebase the browser client performs: pending local operations are
    // transformed against the remote operation, and the remote operation
    // against them, before it is applied locally.
    const pendings = [];
    if (this.inflight) pendings.push(this.inflight);
    pendings.push(...this.outbox);
    const { pending: transformed, remote } = rebase(pendings, { ...broadcast.op });
    if (this.inflight) this.inflight = transformed.shift();
    this.outbox = transformed;
    this.doc = applyOp(this.doc, remote);
  }
}

function mulberry32(seed) {
  return () => {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function runScenario(seed, nClients, nSteps) {
  const rand = mulberry32(seed);
  const server = new Server();
  const clients = Array.from({ length: nClients }, (_, i) => new Client(`user-${i}`, server));
  const wire = []; // pending broadcasts per client: [{clientIdx, broadcast}]

  for (let step = 0; step < nSteps; step++) {
    const c = clients[Math.floor(rand() * nClients)];
    const action = rand();
    if (action < 0.45) {
      c.typeInsert(Math.floor(rand() * (c.doc.length + 1)),
        'abcdefgh'[Math.floor(rand() * 8)] + (rand() < 0.2 ? '\n' : ''));
    } else if (action < 0.65 && c.doc.length) {
      c.typeDelete(Math.floor(rand() * c.doc.length), 1 + Math.floor(rand() * 3));
    } else if (action < 0.85) {
      const msg = c.maybeSend();
      if (msg) {
        const { op, revision } = server.submit(msg.op, msg.baseRevision);
        for (const cl of clients) wire.push({ client: cl, broadcast: { op, revision, from: msg.from } });
      }
    } else if (wire.length) {
      // Deliver the oldest broadcast for a random client (per-client FIFO order)
      const idx = wire.findIndex((_, i) => rand() < 0.5 || i === wire.length - 1);
      // find first entry for that entry's client to preserve ordering
      const target = wire[idx].client;
      const first = wire.findIndex(w => w.client === target);
      target.receive(wire[first].broadcast);
      wire.splice(first, 1);
    }
  }

  // Drain: flush all sends and deliveries until quiescent
  let progress = true;
  while (progress) {
    progress = false;
    for (const c of clients) {
      const msg = c.maybeSend();
      if (msg) {
        const { op, revision } = server.submit(msg.op, msg.baseRevision);
        for (const cl of clients) wire.push({ client: cl, broadcast: { op, revision, from: msg.from } });
        progress = true;
      }
    }
    while (wire.length) {
      const w = wire.shift();
      w.client.receive(w.broadcast);
      progress = true;
    }
  }

  for (const c of clients) {
    if (c.doc !== server.doc) {
      console.error(`DIVERGED seed=${seed} client=${c.id}`);
      console.error(`  server: ${JSON.stringify(server.doc)}`);
      console.error(`  client: ${JSON.stringify(c.doc)}`);
      return false;
    }
  }
  return true;
}

let failures = 0;
const scenarios = 500;
for (let seed = 1; seed <= scenarios; seed++) {
  if (!runScenario(seed, 2 + (seed % 3), 120)) failures++;
}
if (failures) {
  console.error(`${failures}/${scenarios} scenarios diverged`);
  process.exit(1);
}
console.log(`OK: ${scenarios} randomized scenarios (2-4 clients, 120 steps each) all converged`);
