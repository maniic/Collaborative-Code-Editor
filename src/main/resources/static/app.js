/* ============================================================
 * Collaborative Code Editor — browser client
 *
 * Talks to the Spring Boot backend:
 *   REST  /api/auth, /api/sessions            (JWT bearer auth)
 *   WS    /ws/sessions/{id}?access_token=...  (collaboration protocol)
 *
 * The OT engine in ot.js mirrors the server's transform rules exactly,
 * so pending local edits can be rebased against incoming canonical
 * operations and every participant converges on the same document.
 * ============================================================ */

import { applyOp, isNoop, rebase, transformCaret } from './ot.js';
import { createEditor } from './editor.js';

/* ---------------- State ---------------- */

const state = {
  token: null,
  userId: null,
  email: null,
  authMode: 'signin',
  createLanguage: 'PYTHON',

  session: null,
  editor: null,
  ws: null,

  revision: 0,
  doc: '',
  inflight: null,      // operation sent, awaiting ack
  inflightId: null,
  outbox: [],          // local operations not yet sent
  // clientOperationIds this connection authored and has not yet seen echoed
  // back. Operations are recognised as our own by id, never by author: the
  // same user may have two tabs in one room, and each must apply the other's
  // operations rather than discard them.
  ownOperationIds: new Set(),

  participants: new Map(),  // userId -> { userId, email }
  presence: new Map(),      // userId -> { start, end }

  reconnectDelay: 1000,
  reconnectAttempts: 0,
  connectedThisAttempt: false,
  intentionalClose: false,

  execution: null,
};

const MAX_RECONNECT_ATTEMPTS = 6;
const PRESENCE_THROTTLE_MS = 120;
const CARET_LABEL_MS = 2200;

/* A fixed palette assigned by user id, so a participant keeps the same colour
 * across reloads and looks the same to everyone in the room. */
const PARTICIPANT_COLORS = [
  '#5b8cff', '#3ddc84', '#ffc857', '#ff8fa3',
  '#c084fc', '#4dd4c0', '#ff922b', '#7dd3fc',
];

function colorFor(userId) {
  const key = String(userId);
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) >>> 0;
  return PARTICIPANT_COLORS[hash % PARTICIPANT_COLORS.length];
}

/* The local part of the email, capped so a long address cannot stretch a
 * caret label across the editor. */
function displayName(email) {
  if (!email) return 'Someone';
  const local = email.split('@')[0];
  return local.length > 16 ? `${local.slice(0, 15)}…` : local;
}

function initials(email) {
  return displayName(email).slice(0, 2).toUpperCase();
}

/* ---------------- DOM helpers ---------------- */

const $ = (id) => document.getElementById(id);

function show(viewId) {
  for (const id of ['view-auth', 'view-lobby', 'view-editor']) {
    $(id).classList.toggle('hidden', id !== viewId);
  }
}

function toast(message, kind = 'info') {
  const el = document.createElement('div');
  el.className = `toast toast-${kind}`;
  const dot = document.createElement('span');
  dot.className = 'toast-dot';
  const text = document.createElement('span');
  text.textContent = message;
  el.append(dot, text);
  $('toasts').appendChild(el);
  setTimeout(() => {
    el.classList.add('is-leaving');
    setTimeout(() => el.remove(), 200);
  }, 3600);
}

function setBusy(button, busy) {
  button.classList.toggle('is-busy', busy);
  button.disabled = busy;
}

/* ---------------- REST ---------------- */

class ApiError extends Error {
  constructor(message, status, fieldErrors) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors || {};
  }
}

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;

  const res = await fetch(path, { ...options, headers });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    let fieldErrors = {};
    try {
      const body = await res.json();
      if (body) {
        message = body.message || body.error || message;
        fieldErrors = body.fieldErrors || {};
      }
    } catch { /* non-JSON error body */ }
    throw new ApiError(message, res.status, fieldErrors);
  }
  return res.status === 204 ? null : res.json().catch(() => null);
}

/* ---------------- Auth ---------------- */

function setAuthMode(mode) {
  state.authMode = mode;
  const signingIn = mode === 'signin';
  $('tab-signin').classList.toggle('is-active', signingIn);
  $('tab-signup').classList.toggle('is-active', !signingIn);
  $('tab-signin').setAttribute('aria-selected', String(signingIn));
  $('tab-signup').setAttribute('aria-selected', String(!signingIn));
  $('auth-submit').querySelector('.btn-label').textContent = signingIn ? 'Sign in' : 'Create account';
  $('auth-password').setAttribute('autocomplete', signingIn ? 'current-password' : 'new-password');
  clearAuthErrors();
}

function clearAuthErrors() {
  $('auth-form-error').textContent = '';
  for (const field of ['email', 'password']) {
    $(`auth-${field}-error`).textContent = '';
    $(`auth-${field}`).classList.remove('is-invalid');
  }
}

function setFieldError(field, message) {
  $(`auth-${field}-error`).textContent = message;
  $(`auth-${field}`).classList.toggle('is-invalid', Boolean(message));
}

/** Client-side checks that mirror the server's constraints, so obvious
 *  mistakes are reported without a round trip. */
function validateAuthInput(email, password) {
  let ok = true;
  if (!email) {
    setFieldError('email', 'Enter your email address.');
    ok = false;
  } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    setFieldError('email', 'That does not look like an email address.');
    ok = false;
  }
  if (!password) {
    setFieldError('password', 'Enter your password.');
    ok = false;
  } else if (state.authMode === 'signup' && password.length < 8) {
    setFieldError('password', 'Use at least 8 characters.');
    ok = false;
  }
  return ok;
}

async function submitAuth(event) {
  event.preventDefault();
  clearAuthErrors();

  const email = $('auth-email').value.trim();
  const password = $('auth-password').value;
  if (!validateAuthInput(email, password)) return;

  const button = $('auth-submit');
  setBusy(button, true);
  try {
    if (state.authMode === 'signup') {
      await api('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password }) });
    }
    const data = await api('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    state.token = data.accessToken;
    state.userId = data.userId;
    state.email = data.email;
    $('lobby-user').textContent = data.email;
    $('auth-password').value = '';
    await refreshSessions();
    show('view-lobby');
  } catch (err) {
    showAuthError(err);
  } finally {
    setBusy(button, false);
  }
}

function showAuthError(err) {
  if (err instanceof ApiError && Object.keys(err.fieldErrors).length) {
    for (const [field, message] of Object.entries(err.fieldErrors)) {
      if (field === 'email' || field === 'password') setFieldError(field, capitalize(message));
    }
    return;
  }
  if (err instanceof ApiError && err.status === 401) {
    $('auth-form-error').textContent = 'That email and password do not match an account.';
    return;
  }
  if (err instanceof ApiError && err.status === 409) {
    $('auth-form-error').textContent = 'An account with that email already exists. Try signing in.';
    return;
  }
  $('auth-form-error').textContent = err.message || 'Something went wrong. Try again.';
}

const capitalize = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

function signOut() {
  closeSocket();
  state.token = null;
  state.userId = null;
  state.email = null;
  state.session = null;
  $('auth-email').value = '';
  $('auth-password').value = '';
  clearAuthErrors();
  show('view-auth');
}

/* ---------------- Lobby ---------------- */

async function refreshSessions() {
  const sessions = await api('/api/sessions');
  const list = $('session-list');
  list.replaceChildren();

  if (!sessions || !sessions.length) {
    const empty = document.createElement('li');
    empty.className = 'empty-state';
    empty.textContent = 'No sessions yet — create one, or join with an invite code.';
    list.appendChild(empty);
    return;
  }

  for (const session of sessions) {
    list.appendChild(renderSessionRow(session));
  }
}

function renderSessionRow(session) {
  const row = document.createElement('li');
  row.className = 'session-row';

  const main = document.createElement('div');
  main.className = 'session-main';

  const code = document.createElement('div');
  code.className = 'session-code';
  code.textContent = session.inviteCode;

  const facts = document.createElement('div');
  facts.className = 'session-facts';
  const count = session.activeParticipants;
  facts.append(
    text(`${count} ${count === 1 ? 'participant' : 'participants'}`),
    separator(),
    text(relativeTime(session.createdAt)),
  );

  main.append(code, facts);

  const badge = document.createElement('span');
  const language = String(session.language || '').toUpperCase();
  badge.className = `badge badge-${language.toLowerCase()}`;
  badge.textContent = language;

  const open = document.createElement('button');
  open.className = 'btn btn-secondary btn-sm';
  open.textContent = 'Open';
  open.addEventListener('click', () => enterSession(session));

  row.append(main, badge, open);
  return row;
}

const text = (value) => document.createTextNode(value);

function separator() {
  const el = document.createElement('span');
  el.className = 'fact-sep';
  el.textContent = '·';
  return el;
}

function relativeTime(iso) {
  if (!iso) return 'just now';
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  const units = [
    [60, 'minute'], [24, 'hour'], [7, 'day'], [4.35, 'week'], [12, 'month'],
  ];
  let value = seconds / 60;
  let unit = 'minute';
  for (const [step, name] of units) {
    if (value < step) { unit = name; break; }
    if (name !== 'month') value /= step;
    unit = name;
  }
  const rounded = Math.floor(value);
  return `${rounded} ${unit}${rounded === 1 ? '' : 's'} ago`;
}

async function createSession() {
  const button = $('create-btn');
  setBusy(button, true);
  try {
    const session = await api('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ language: state.createLanguage }),
    });
    enterSession(session);
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    setBusy(button, false);
  }
}

async function joinSession(event) {
  event.preventDefault();
  $('join-error').textContent = '';
  const inviteCode = $('join-code').value.trim().toUpperCase();

  if (inviteCode.length !== 8) {
    $('join-error').textContent = 'Invite codes are 8 characters.';
    $('join-code').classList.add('is-invalid');
    return;
  }
  $('join-code').classList.remove('is-invalid');

  const button = $('join-btn');
  setBusy(button, true);
  try {
    const session = await api('/api/sessions/join', {
      method: 'POST',
      body: JSON.stringify({ inviteCode }),
    });
    $('join-code').value = '';
    enterSession(session);
  } catch (err) {
    $('join-error').textContent = err.status === 404
      ? 'No session found with that invite code.'
      : err.message;
    $('join-code').classList.add('is-invalid');
  } finally {
    setBusy(button, false);
  }
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
  state.presence = new Map();
  state.reconnectDelay = 1000;
  state.reconnectAttempts = 0;
  state.intentionalClose = false;
  state.execution = null;

  $('editor-invite').textContent = session.inviteCode;
  const language = String(session.language || '').toUpperCase();
  const badge = $('editor-language');
  badge.textContent = language;
  badge.className = `badge badge-${language.toLowerCase()}`;
  $('revision').textContent = 'rev 0';
  $('run-btn').disabled = true;
  renderConsoleEmpty();
  renderParticipants();

  if (state.editor) state.editor.destroy();
  state.editor = createEditor({
    parent: $('editor-host'),
    language,
    onOperations: queueLocalOperations,
    onSelection: queueSelection,
  });
  state.editor.setEditable(false);

  show('view-editor');
  connect();
}

function leaveSession() {
  const sessionId = state.session?.sessionId;
  closeSocket();
  if (sessionId) {
    api(`/api/sessions/${sessionId}/leave`, { method: 'POST' }).catch(() => {});
  }
  teardownEditor();
  refreshSessions().catch(() => {});
  show('view-lobby');
}

function teardownEditor() {
  state.session = null;
  if (state.editor) {
    state.editor.destroy();
    state.editor = null;
  }
}

function closeSocket() {
  state.intentionalClose = true;
  if (state.ws) {
    state.ws.close();
    state.ws = null;
  }
}

function setConnStatus(label, modifier) {
  const el = $('conn-status');
  el.className = `status status-${modifier}`;
  el.querySelector('.status-text').textContent = label;
}

/* ---------------- WebSocket ---------------- */

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${proto}://${location.host}/ws/sessions/${state.session.sessionId}`
            + `?access_token=${encodeURIComponent(state.token)}`;

  setConnStatus('connecting', 'connecting');
  state.connectedThisAttempt = false;

  const ws = new WebSocket(url);
  state.ws = ws;

  ws.onopen = () => {
    state.connectedThisAttempt = true;
    state.reconnectDelay = 1000;
    state.reconnectAttempts = 0;
  };

  ws.onmessage = (event) => {
    const { type, payload } = JSON.parse(event.data);
    handleServerMessage(type, payload);
  };

  // A failed handshake fires an error event before close; the browser gives no
  // way to read its status, so nothing is logged here — scheduleReconnect works
  // out whether retrying can help.
  ws.onerror = () => {};

  ws.onclose = () => {
    if (state.intentionalClose || !state.session) return;
    if (state.editor) state.editor.setEditable(false);
    $('run-btn').disabled = true;
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
    teardownEditor();
    state.token = null;
    setConnStatus('signed out', 'offline');
    toast('Your session expired — please sign in again.', 'error');
    show('view-auth');
    return;
  }

  state.reconnectAttempts += 1;
  if (state.reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
    setConnStatus('offline', 'offline');
    toast('Lost connection to this session.', 'error');
    teardownEditor();
    refreshSessions().catch(() => {});
    show('view-lobby');
    return;
  }

  setConnStatus('reconnecting', 'reconnecting');
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

function handleServerMessage(type, payload) {
  switch (type) {
    case 'document_sync':
      onDocumentSync(payload);
      break;

    case 'operation_ack':
      if (payload.clientOperationId === state.inflightId) {
        state.inflight = null;
        state.inflightId = null;
      }
      setRevision(payload.revision);
      sendNextOperation();
      break;

    case 'operation_applied':
      setRevision(payload.revision);
      // Skip the echo of an operation this connection sent — matched by
      // operation id, not by author, so a second tab signed in as the same
      // user still applies its counterpart's edits. The id is consumed on
      // arrival, which also makes this correct if the ack races ahead of the
      // broadcast.
      if (payload.clientOperationId && state.ownOperationIds.delete(payload.clientOperationId)) break;
      applyRemoteOperation(payload);
      break;

    case 'operation_error':
      // The server rejected a submission. Drop the in-flight operation so the
      // queue keeps draining rather than stalling behind it.
      state.inflight = null;
      state.inflightId = null;
      toast(payload.message || 'That edit was rejected.', 'error');
      sendNextOperation();
      break;

    case 'resync_required':
      setConnStatus('resyncing', 'resyncing');
      toast('Resyncing with the server…', 'info');
      closeSocket();
      state.intentionalClose = false;
      setTimeout(() => { if (state.session) connect(); }, 250);
      break;

    case 'participant_joined': {
      const participant = payload.participant ?? { userId: payload.userId, email: payload.email };
      if (participant.userId !== state.userId) {
        toast(`${displayName(participant.email)} joined`, 'success');
      }
      state.participants.set(participant.userId, participant);
      renderParticipants();
      break;
    }

    case 'participant_left': {
      const leaving = state.participants.get(payload.userId);
      if (payload.userId !== state.userId && leaving) {
        toast(`${displayName(leaving.email)} left`, 'info');
      }
      state.participants.delete(payload.userId);
      state.presence.delete(payload.userId);
      renderParticipants();
      renderRemotePresence();
      break;
    }

    case 'presence_updated':
      if (payload.userId !== state.userId && payload.selection) {
        state.presence.set(payload.userId, {
          start: payload.selection.start,
          end: payload.selection.end,
        });
        if (!state.participants.has(payload.userId)) {
          state.participants.set(payload.userId, { userId: payload.userId, email: payload.email });
          renderParticipants();
        }
        renderRemotePresence();
      }
      break;

    case 'execution_updated':
      renderExecution(payload);
      break;

    default:
      break;
  }
}

function onDocumentSync(payload) {
  // Fresh bootstrap: any unacknowledged local edits are dropped.
  state.doc = payload.document;
  state.revision = payload.revision;
  state.inflight = null;
  state.inflightId = null;
  state.ownOperationIds.clear();
  state.outbox = [];
  state.presence.clear();

  state.editor.replaceDocument(state.doc);
  state.editor.setEditable(true);
  state.participants = new Map(payload.participants.map((p) => [p.userId, p]));

  renderParticipants();
  renderRemotePresence();
  setRevision(payload.revision);
  setConnStatus('live', 'live');
  $('run-btn').disabled = false;
}

function setRevision(revision) {
  state.revision = Math.max(state.revision, revision);
  $('revision').textContent = `rev ${state.revision}`;
}

/* ---------------- Local edits ---------------- */

function queueLocalOperations(operations) {
  for (const op of operations) {
    state.outbox.push({ ...op, author: String(state.userId) });
    state.doc = applyOp(state.doc, op);
  }
  sendNextOperation();
}

function sendNextOperation() {
  if (state.inflight || !state.outbox.length) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;

  const op = state.outbox.shift();
  if (isNoop(op)) { sendNextOperation(); return; }

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

/* Incorporate a canonical remote operation while local edits are pending.
 * The server transforms pending submissions against the canonical log with the
 * same rules, so the client mirrors it: pending' = T(pending, remote) and
 * remote' = T(remote, pending). */
function applyRemoteOperation(payload) {
  const incoming = {
    operationType: payload.operationType,
    position: payload.position,
    text: payload.text ?? undefined,
    length: payload.length ?? undefined,
    author: String(payload.userId),
  };

  const pending = [];
  if (state.inflight) pending.push(state.inflight);
  pending.push(...state.outbox);

  const { pending: rebased, remote } = rebase(pending, incoming);
  if (state.inflight) state.inflight = rebased.shift();
  state.outbox = rebased;

  state.doc = applyOp(state.doc, remote);

  // Remote carets are stored as absolute offsets; shift them through the
  // operation so they stay anchored until the next presence broadcast.
  for (const [userId, selection] of state.presence) {
    state.presence.set(userId, {
      start: transformCaret(selection.start, remote),
      end: transformCaret(selection.end, remote),
    });
  }

  state.editor.applyRemoteOperation(remote);
  renderRemotePresence();
}

/* ---------------- Presence ---------------- */

let presenceTimer = null;
let pendingSelection = null;

function queueSelection(selection) {
  pendingSelection = selection;
  if (presenceTimer) return;
  presenceTimer = setTimeout(() => {
    presenceTimer = null;
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN || !pendingSelection) return;
    state.ws.send(JSON.stringify({
      type: 'update_presence',
      payload: { selection: pendingSelection },
    }));
  }, PRESENCE_THROTTLE_MS);
}

function renderParticipants() {
  const host = $('participants');
  host.replaceChildren();

  const all = [...state.participants.values()].filter((p) => p && p.userId);
  const visible = all.slice(0, 5);

  for (const participant of visible) {
    const avatar = document.createElement('span');
    const isSelf = participant.userId === state.userId;
    avatar.className = `avatar${isSelf ? ' is-self' : ''}`;
    avatar.style.background = colorFor(participant.userId);
    avatar.textContent = initials(participant.email);
    avatar.title = isSelf ? `${participant.email} (you)` : participant.email;
    host.appendChild(avatar);
  }

  if (all.length > visible.length) {
    const more = document.createElement('span');
    more.className = 'avatar avatar-more';
    more.textContent = `+${all.length - visible.length}`;
    more.title = all.slice(visible.length).map((p) => p.email).join('\n');
    host.appendChild(more);
  }
}

let caretLabelTimer = null;

function renderRemotePresence() {
  if (!state.editor) return;

  const presences = [];
  for (const [userId, selection] of state.presence) {
    if (userId === state.userId) continue;
    const participant = state.participants.get(userId);
    presences.push({
      userId,
      from: selection.start,
      to: selection.end,
      color: colorFor(userId),
      name: displayName(participant?.email),
    });
  }
  state.editor.setRemotePresence(presences);

  // Reveal name labels briefly whenever presence changes, then let them fade.
  const host = $('editor-host');
  host.querySelectorAll('.cm-remote-caret').forEach((el) => el.classList.add('is-fresh'));
  clearTimeout(caretLabelTimer);
  caretLabelTimer = setTimeout(() => {
    host.querySelectorAll('.cm-remote-caret').forEach((el) => el.classList.remove('is-fresh'));
  }, CARET_LABEL_MS);
}

/* ---------------- Execution ---------------- */

const TERMINAL_EXECUTION_STATUSES = ['COMPLETED', 'FAILED', 'TIMED_OUT', 'REJECTED', 'ERROR'];

async function runCode() {
  const button = $('run-btn');
  setBusy(button, true);
  try {
    await api(`/api/sessions/${state.session.sessionId}/executions`, { method: 'POST' });
  } catch (err) {
    setBusy(button, false);
    toast(err.message, 'error');
  }
}

function renderExecution(payload) {
  state.execution = payload;
  renderConsoleMeta(payload);
  renderConsoleBody(payload);

  if (TERMINAL_EXECUTION_STATUSES.includes(payload.status)) {
    setBusy($('run-btn'), false);
    // The Run button is only meaningful while connected.
    $('run-btn').disabled = !state.ws || state.ws.readyState !== WebSocket.OPEN;
  }
}

function renderConsoleMeta(payload) {
  const meta = $('exec-meta');
  meta.replaceChildren();

  const status = document.createElement('span');
  status.className = `run-status is-${String(payload.status || '').toLowerCase()}`;
  status.textContent = payload.exitCode != null
    ? `${payload.status} · exit ${payload.exitCode}`
    : payload.status;
  meta.appendChild(status);

  const duration = elapsed(payload.startedAt, payload.finishedAt);
  if (duration) {
    const el = document.createElement('span');
    el.textContent = duration;
    meta.appendChild(el);
  }

  if (payload.requestedByEmail) {
    const who = document.createElement('span');
    who.className = 'run-by';
    who.textContent = payload.requestedByUserId === state.userId
      ? 'by you'
      : `by ${displayName(payload.requestedByEmail)}`;
    who.title = payload.requestedByEmail;
    meta.appendChild(who);
  }
}

function elapsed(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) return null;
  const ms = new Date(finishedAt).getTime() - new Date(startedAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) return null;
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function renderConsoleBody(payload) {
  const body = $('console-body');
  body.replaceChildren();

  const hasOutput = Boolean(payload.stdout || payload.stderr);
  if (payload.stdout) body.appendChild(stream('stdout', payload.stdout));
  if (payload.stderr) body.appendChild(stream('stderr', payload.stderr));
  if (!hasOutput && payload.message) body.appendChild(stream('note', payload.message));
  if (!hasOutput && !payload.message) renderConsoleEmpty();
}

function stream(kind, content) {
  const wrap = document.createElement('div');
  wrap.className = `stream stream-${kind}`;
  if (kind !== 'note') {
    const label = document.createElement('div');
    label.className = 'stream-label';
    label.textContent = kind;
    wrap.appendChild(label);
  }
  const pre = document.createElement('pre');
  pre.textContent = content;
  wrap.appendChild(pre);
  return wrap;
}

function renderConsoleEmpty() {
  const body = $('console-body');
  body.replaceChildren();
  $('exec-meta').replaceChildren();
  const p = document.createElement('p');
  p.className = 'console-empty';
  p.textContent = 'Run the document to execute it in a sandboxed container. '
                + 'Output is shared with everyone in the session.';
  body.appendChild(p);
}

/* ---------------- Invite code ---------------- */

/* The async Clipboard API is unavailable outside secure contexts and can be
 * denied by permission policy, so the promise must be handled — an unhandled
 * rejection here would log an error while the UI claimed success. */
async function copyInvite() {
  const code = state.session?.inviteCode;
  if (!code) return;
  try {
    await navigator.clipboard.writeText(code);
    toast('Invite code copied', 'success');
  } catch {
    toast(`Copy failed — the invite code is ${code}`, 'error');
  }
}

/* ---------------- Wiring ---------------- */

document.addEventListener('DOMContentLoaded', () => {
  $('tab-signin').addEventListener('click', () => setAuthMode('signin'));
  $('tab-signup').addEventListener('click', () => setAuthMode('signup'));
  $('auth-form').addEventListener('submit', submitAuth);
  $('auth-email').addEventListener('input', () => setFieldError('email', ''));
  $('auth-password').addEventListener('input', () => setFieldError('password', ''));

  $('signout-btn').addEventListener('click', signOut);

  for (const segment of document.querySelectorAll('.segment')) {
    segment.addEventListener('click', () => {
      state.createLanguage = segment.dataset.language;
      for (const other of document.querySelectorAll('.segment')) {
        const active = other === segment;
        other.classList.toggle('is-active', active);
        other.setAttribute('aria-checked', String(active));
      }
    });
  }

  $('create-btn').addEventListener('click', createSession);
  $('join-form').addEventListener('submit', joinSession);
  $('join-code').addEventListener('input', () => {
    $('join-error').textContent = '';
    $('join-code').classList.remove('is-invalid');
  });
  $('refresh-btn').addEventListener('click', () => {
    refreshSessions().catch((err) => toast(err.message, 'error'));
  });

  $('leave-btn').addEventListener('click', leaveSession);
  $('copy-invite').addEventListener('click', copyInvite);
  $('run-btn').addEventListener('click', runCode);

  setAuthMode('signin');
});
