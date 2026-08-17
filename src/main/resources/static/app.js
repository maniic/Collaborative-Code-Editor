'use strict';

/* ============================================================
 * Collaborative Code Editor — browser client
 *
 * Talks to the Spring Boot backend:
 *   REST  /api/auth, /api/sessions            (JWT bearer auth)
 *   WS    /ws/sessions/{id}?access_token=...  (collaboration protocol)
 *
 * The OT client mirrors OperationalTransformService on the server —
 * identical transform rules and author tie-breaks — so that documents
 * converge for every participant.
 * ============================================================ */

/* ---------------- OT: operations and transforms ---------------- */

// op: { operationType: 'INSERT'|'DELETE', position, text?, length?, author }
// author is the userId string, used for the same-position insert tie-break.

function transformOp(op1, op2) {
  if (isNoop(op1) || isNoop(op2)) return { ...op1 };
  if (op1.operationType === 'INSERT') {
    return op2.operationType === 'INSERT'
      ? xInsertInsert(op1, op2)
      : xInsertDelete(op1, op2);
  }
  return op2.operationType === 'INSERT'
    ? xDeleteInsert(op1, op2)
    : xDeleteDelete(op1, op2);
}

function isNoop(op) {
  return op.operationType === 'DELETE' && op.length === 0;
}

function xInsertInsert(op1, op2) {
  if (op1.position < op2.position) return { ...op1 };
  if (op1.position > op2.position) return { ...op1, position: op1.position + op2.text.length };
  // Same position: lower author UUID string keeps the left position.
  return op1.author < op2.author
    ? { ...op1 }
    : { ...op1, position: op1.position + op2.text.length };
}

function xInsertDelete(ins, del) {
  if (ins.position <= del.position) return { ...ins };
  if (ins.position >= del.position + del.length) return { ...ins, position: ins.position - del.length };
  // Strictly inside the delete range: annulled (the concurrent delete
  // expands to swallow this insert — see xDeleteInsert), mirroring the server.
  return { operationType: 'DELETE', position: del.position, length: 0, author: ins.author };
}

function xDeleteInsert(del, ins) {
  const delEnd = del.position + del.length;
  if (delEnd <= ins.position) return { ...del };
  if (del.position >= ins.position) return { ...del, position: del.position + ins.text.length };
  return { ...del, length: del.length + ins.text.length };
}

function xDeleteDelete(op1, op2) {
  const op1End = op1.position + op1.length;
  const op2End = op2.position + op2.length;
  if (op1End <= op2.position) return { ...op1 };
  if (op1.position >= op2End) return { ...op1, position: op1.position - op2.length };
  const overlap = Math.min(op1End, op2End) - Math.max(op1.position, op2.position);
  const deletedBefore = Math.max(0, Math.min(op2End, op1.position) - op2.position);
  return { ...op1, position: op1.position - deletedBefore, length: op1.length - overlap };
}

function applyOp(doc, op) {
  if (isNoop(op)) return doc;
  if (op.operationType === 'INSERT') {
    return doc.slice(0, op.position) + op.text + doc.slice(op.position);
  }
  return doc.slice(0, op.position) + doc.slice(op.position + op.length);
}

// Shift a caret offset through an operation.
function transformCaret(caret, op) {
  if (isNoop(op)) return caret;
  if (op.operationType === 'INSERT') {
    return op.position <= caret ? caret + op.text.length : caret;
  }
  if (caret <= op.position) return caret;
  return Math.max(op.position, caret - op.length);
}

/* ---------------- App state ---------------- */

const state = {
  token: null,
  userId: null,
  email: null,
  session: null,      // SessionResponse of the joined session
  ws: null,
  revision: 0,
  doc: '',
  inflight: null,     // op sent, awaiting ack
  inflightId: null,
  // clientOperationIds this connection authored and has not yet seen echoed
  // back. Operations are recognised as our own by id, never by author: the
  // same user may have two tabs in one room, and each must apply the other's
  // operations rather than discard them.
  ownOperationIds: new Set(),
  outbox: [],         // local ops not yet sent
  participants: new Map(),
  reconnectDelay: 1000,
  reconnectAttempts: 0,
  connectedThisAttempt: false,
  intentionalClose: false,
};

const MAX_RECONNECT_ATTEMPTS = 6;

/* ---------------- Tiny DOM helpers ---------------- */

const $ = (id) => document.getElementById(id);
const editor = () => $('editor');

function show(viewId) {
  for (const v of ['view-auth', 'view-lobby', 'view-editor']) {
    $(v).classList.toggle('hidden', v !== viewId);
  }
}

function toast(message, isError = true) {
  const el = $('toast');
  el.textContent = message;
  el.className = isError ? 'toast error' : 'toast';
  el.classList.remove('hidden');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.add('hidden'), 4000);
}

/* ---------------- REST ---------------- */

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  const res = await fetch(path, { ...options, headers });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      message = body.message || body.error || (body.errors && JSON.stringify(body.errors)) || message;
    } catch { /* non-JSON error body */ }
    throw new Error(message);
  }
  return res.status === 204 ? null : res.json().catch(() => null);
}

/* ---------------- Auth ---------------- */

async function login() {
  const email = $('auth-email').value.trim();
  const password = $('auth-password').value;
  const data = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  state.token = data.accessToken;
  state.userId = data.userId;
  state.email = data.email;
  $('lobby-user').textContent = data.email;
  await refreshSessions();
  show('view-lobby');
}

async function register() {
  const email = $('auth-email').value.trim();
  const password = $('auth-password').value;
  await api('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  toast('Account created — signing in…', false);
  await login();
}

/* ---------------- Lobby ---------------- */

async function refreshSessions() {
  const sessions = await api('/api/sessions');
  const list = $('session-list');
  list.innerHTML = '';
  if (!sessions.length) {
    list.innerHTML = '<li class="empty">No active sessions — create one.</li>';
    return;
  }
  for (const s of sessions) {
    const li = document.createElement('li');
    const label = document.createElement('span');
    label.innerHTML = `<code>${s.inviteCode}</code> · ${s.language} · ${s.activeParticipants} online`;
    const btn = document.createElement('button');
    btn.textContent = 'Open';
    btn.addEventListener('click', () => enterSession(s));
    li.append(label, btn);
    list.appendChild(li);
  }
}

async function createSession() {
  const language = $('create-language').value;
  const session = await api('/api/sessions', {
    method: 'POST',
    body: JSON.stringify({ language }),
  });
  enterSession(session);
}

async function joinSession() {
  const inviteCode = $('join-code').value.trim().toUpperCase();
  const session = await api('/api/sessions/join', {
    method: 'POST',
    body: JSON.stringify({ inviteCode }),
  });
  enterSession(session);
}

/* ---------------- Editor session ---------------- */

function enterSession(session) {
  state.session = session;
  state.revision = 0;
  state.doc = '';
  state.inflight = null;
  state.inflightId = null;
  state.ownOperationIds.clear();
  state.outbox = [];
  state.participants = new Map();
  state.reconnectDelay = 1000;
  state.reconnectAttempts = 0;
  state.intentionalClose = false;
  $('editor-invite').textContent = session.inviteCode;
  $('editor-language').textContent = session.language;
  $('exec-output').textContent = '';
  $('exec-status').textContent = '';
  editor().value = '';
  editor().disabled = true;
  show('view-editor');
  connect();
}

function leaveSession() {
  state.intentionalClose = true;
  if (state.ws) state.ws.close();
  api(`/api/sessions/${state.session.sessionId}/leave`, { method: 'POST' }).catch(() => {});
  state.session = null;
  refreshSessions().catch(() => {});
  show('view-lobby');
}

function setConnStatus(text, cls) {
  const el = $('conn-status');
  el.textContent = text;
  el.className = `pill ${cls}`;
}

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${proto}://${location.host}/ws/sessions/${state.session.sessionId}` +
              `?access_token=${encodeURIComponent(state.token)}`;
  setConnStatus('connecting…', 'warn');
  state.connectedThisAttempt = false;
  const ws = new WebSocket(url);
  state.ws = ws;

  ws.onopen = () => {
    state.connectedThisAttempt = true;
    state.reconnectDelay = 1000;
    state.reconnectAttempts = 0;
    setConnStatus('live', 'ok');
  };

  ws.onmessage = (event) => {
    const { type, payload } = JSON.parse(event.data);
    handleServerMessage(type, payload);
  };

  ws.onclose = () => {
    if (state.intentionalClose || !state.session) return;
    editor().disabled = true;
    scheduleReconnect();
  };
}

/* Reconnect with backoff, but only while reconnecting can plausibly help.
 *
 * A browser cannot read the HTTP status of a failed WebSocket handshake, so a
 * dead token and a dropped network look identical from here. When a connection
 * fails before it ever opened, probe the REST API with the same token: a 401
 * means the credentials are the problem and no number of retries will fix it. */
async function scheduleReconnect() {
  if (!state.connectedThisAttempt && await tokenIsRejected()) {
    endSessionWithAuthError();
    return;
  }

  state.reconnectAttempts += 1;
  if (state.reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
    setConnStatus('offline', 'err');
    toast('Lost connection to the session. Returning to your sessions.');
    returnToLobby();
    return;
  }

  setConnStatus('reconnecting…', 'warn');
  setTimeout(() => { if (state.session) connect(); }, state.reconnectDelay);
  state.reconnectDelay = Math.min(state.reconnectDelay * 2, 10000);
}

async function tokenIsRejected() {
  try {
    const res = await fetch('/api/sessions', {
      headers: { Authorization: `Bearer ${state.token}` },
    });
    return res.status === 401;
  } catch {
    return false; // network error, not an auth error — keep retrying
  }
}

function endSessionWithAuthError() {
  state.intentionalClose = true;
  state.session = null;
  state.token = null;
  setConnStatus('signed out', 'err');
  toast('Your session expired — please sign in again.');
  show('view-auth');
}

function returnToLobby() {
  state.intentionalClose = true;
  state.session = null;
  refreshSessions().catch(() => {});
  show('view-lobby');
}

function handleServerMessage(type, payload) {
  switch (type) {
    case 'document_sync': {
      // Fresh bootstrap: any unacknowledged local edits are dropped.
      state.doc = payload.document;
      state.revision = payload.revision;
      state.inflight = null;
      state.inflightId = null;
      state.ownOperationIds.clear();
      state.outbox = [];
      editor().value = state.doc;
      editor().disabled = false;
      state.participants = new Map(payload.participants.map(p => [p.userId, p]));
      renderParticipants();
      $('revision').textContent = `rev ${state.revision}`;
      break;
    }
    case 'operation_ack': {
      if (payload.clientOperationId === state.inflightId) {
        state.inflight = null;
        state.inflightId = null;
      }
      state.revision = Math.max(state.revision, payload.revision);
      $('revision').textContent = `rev ${state.revision}`;
      sendNextOp();
      break;
    }
    case 'operation_applied': {
      state.revision = Math.max(state.revision, payload.revision);
      $('revision').textContent = `rev ${state.revision}`;
      // Skip the echo of an operation this connection sent — matched by
      // operation id, not by author, so a second tab signed in as the same
      // user still applies its counterpart's edits. The id is consumed on
      // arrival, which also makes this correct if the ack races ahead of the
      // broadcast.
      if (payload.clientOperationId && state.ownOperationIds.delete(payload.clientOperationId)) break;
      applyRemoteOperation(payload);
      break;
    }
    case 'operation_error':
      toast(`Edit rejected: ${payload.message || payload.code || 'unknown error'}`);
      break;
    case 'resync_required':
      // Server lost confidence in our state; reconnect for a fresh sync.
      toast('Out of sync — reloading document', true);
      if (state.ws) state.ws.close();
      break;
    case 'participant_joined':
      state.participants.set(payload.participant?.userId ?? payload.userId,
        payload.participant ?? { userId: payload.userId, email: payload.email });
      renderParticipants();
      break;
    case 'participant_left':
      state.participants.delete(payload.userId);
      renderParticipants();
      break;
    case 'presence_updated':
      break; // selection sharing not rendered in this client
    case 'execution_updated':
      renderExecution(payload);
      break;
    default:
      break;
  }
}

/* Incorporate a canonical remote operation while local ops are pending.
 * The server transforms our pending ops against the remote op with the same
 * rules, so we mirror it: remote' = T(remote, pending), pending' = T(pending, remote). */
function applyRemoteOperation(payload) {
  let remote = {
    operationType: payload.operationType,
    position: payload.position,
    text: payload.text ?? undefined,
    length: payload.length ?? undefined,
    author: String(payload.userId),
  };

  const pendings = [];
  if (state.inflight) pendings.push(state.inflight);
  pendings.push(...state.outbox);

  const transformedPendings = [];
  for (const p of pendings) {
    transformedPendings.push(transformOp(p, remote));
    remote = transformOp(remote, p);
  }
  if (state.inflight) state.inflight = transformedPendings.shift();
  state.outbox = transformedPendings;

  const el = editor();
  const selStart = transformCaret(el.selectionStart, remote);
  const selEnd = transformCaret(el.selectionEnd, remote);
  state.doc = applyOp(state.doc, remote);
  el.value = state.doc;
  el.setSelectionRange(selStart, selEnd);
}

function sendNextOp() {
  if (state.inflight || !state.outbox.length) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  const op = state.outbox.shift();
  if (isNoop(op)) { sendNextOp(); return; }
  state.inflight = op;
  state.inflightId = crypto.randomUUID();
  state.ownOperationIds.add(state.inflightId);
  state.ws.send(JSON.stringify({
    type: 'submit_operation',
    payload: {
      clientOperationId: state.inflightId,
      baseRevision: state.revision,
      operationType: op.operationType,
      position: op.position,
      text: op.operationType === 'INSERT' ? op.text : null,
      length: op.operationType === 'DELETE' ? op.length : null,
    },
  }));
}

/* Diff the textarea against the last known document and queue ops. */
function onEditorInput() {
  const prev = state.doc;
  const next = editor().value;
  if (prev === next) return;

  let start = 0;
  const maxStart = Math.min(prev.length, next.length);
  while (start < maxStart && prev[start] === next[start]) start++;

  let endPrev = prev.length;
  let endNext = next.length;
  while (endPrev > start && endNext > start && prev[endPrev - 1] === next[endNext - 1]) {
    endPrev--;
    endNext--;
  }

  const deletedLength = endPrev - start;
  const insertedText = next.slice(start, endNext);

  if (deletedLength > 0) {
    state.outbox.push({ operationType: 'DELETE', position: start, length: deletedLength, author: String(state.userId) });
  }
  if (insertedText.length > 0) {
    state.outbox.push({ operationType: 'INSERT', position: start, text: insertedText, author: String(state.userId) });
  }
  state.doc = next;
  sendNextOp();
  sendPresence();
}

let presenceTimer = null;
function sendPresence() {
  if (presenceTimer) return;
  presenceTimer = setTimeout(() => {
    presenceTimer = null;
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    const el = editor();
    state.ws.send(JSON.stringify({
      type: 'update_presence',
      payload: { selection: { start: el.selectionStart, end: el.selectionEnd } },
    }));
  }, 400);
}

/* The async Clipboard API is unavailable outside secure contexts and can be
 * denied by permission policy, so the promise must be handled — an unhandled
 * rejection here used to log an error while the UI claimed success. */
async function copyInvite() {
  const code = state.session?.inviteCode;
  if (!code) return;
  try {
    await navigator.clipboard.writeText(code);
    toast('Invite code copied', false);
  } catch {
    toast(`Copy failed — the invite code is ${code}`);
  }
}

function renderParticipants() {
  const el = $('participants');
  el.innerHTML = '';
  for (const p of state.participants.values()) {
    if (!p || !p.email) continue;
    const chip = document.createElement('span');
    chip.className = 'chip' + (p.userId === state.userId ? ' me' : '');
    chip.textContent = p.email;
    el.appendChild(chip);
  }
}

/* ---------------- Code execution ---------------- */

async function runCode() {
  $('run-btn').disabled = true;
  $('exec-status').textContent = 'queued…';
  $('exec-output').textContent = '';
  try {
    await api(`/api/sessions/${state.session.sessionId}/executions`, { method: 'POST' });
  } catch (err) {
    $('exec-status').textContent = '';
    toast(`Run failed: ${err.message}`);
    $('run-btn').disabled = false;
  }
}

const TERMINAL_EXECUTION_STATUSES = ['COMPLETED', 'FAILED', 'TIMED_OUT', 'REJECTED', 'ERROR'];

function renderExecution(payload) {
  const status = payload.status || '';
  const who = payload.requestedByEmail
    ? ` · ${payload.requestedByUserId === state.userId ? 'you' : payload.requestedByEmail}`
    : '';
  $('exec-status').textContent = status.toLowerCase() +
    (payload.exitCode != null ? ` (exit ${payload.exitCode})` : '') + who;
  const parts = [];
  if (payload.stdout) parts.push(payload.stdout);
  if (payload.stderr) parts.push(`--- stderr ---\n${payload.stderr}`);
  if (payload.message && !payload.stdout && !payload.stderr) parts.push(payload.message);
  $('exec-output').textContent = parts.join('\n');
  // REJECTED belongs here too: a cooldown rejection arriving over the socket
  // must release the Run button, not leave it stuck disabled.
  if (TERMINAL_EXECUTION_STATUSES.includes(status)) {
    $('run-btn').disabled = false;
  }
}

/* ---------------- Wiring ---------------- */

function guard(fn, btn) {
  return async () => {
    if (btn) btn.disabled = true;
    try {
      await fn();
    } catch (err) {
      toast(err.message);
    } finally {
      if (btn) btn.disabled = false;
    }
  };
}

if (typeof document !== 'undefined') document.addEventListener('DOMContentLoaded', () => {
  $('login-btn').addEventListener('click', guard(login, $('login-btn')));
  $('register-btn').addEventListener('click', guard(register, $('register-btn')));
  $('auth-password').addEventListener('keydown', e => { if (e.key === 'Enter') guard(login, $('login-btn'))(); });
  $('create-btn').addEventListener('click', guard(createSession, $('create-btn')));
  $('join-btn').addEventListener('click', guard(joinSession, $('join-btn')));
  $('refresh-btn').addEventListener('click', guard(refreshSessions, $('refresh-btn')));
  $('leave-btn').addEventListener('click', () => leaveSession());
  $('run-btn').addEventListener('click', () => runCode());
  $('copy-invite').addEventListener('click', () => { copyInvite(); });
  editor().addEventListener('input', onEditorInput);
  editor().addEventListener('keydown', (e) => {
    if (e.key === 'Tab') { // insert spaces instead of losing focus
      e.preventDefault();
      const el = editor();
      const { selectionStart, selectionEnd, value } = el;
      el.value = value.slice(0, selectionStart) + '    ' + value.slice(selectionEnd);
      el.setSelectionRange(selectionStart + 4, selectionStart + 4);
      onEditorInput();
    }
  });
  editor().addEventListener('select', sendPresence);
});

// Exported for the node-based convergence test (scripts/test-ot-client.mjs).
if (typeof module !== 'undefined') {
  module.exports = { transformOp, applyOp, transformCaret };
}
