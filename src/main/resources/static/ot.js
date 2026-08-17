/* ============================================================
 * Client-side Operational Transform engine.
 *
 * A faithful mirror of the server's OperationalTransformService:
 * identical transform rules, identical same-position insert
 * tie-break, identical insert-annulled-by-delete rule. The client
 * must agree with the server exactly, or documents diverge.
 *
 * Pure functions only — no DOM, no network. Exercised directly by
 * scripts/test-ot-client.mjs across 500 randomized scenarios.
 *
 * op: { operationType: 'INSERT'|'DELETE', position, text?, length?, author }
 * `author` is the userId string, used for the same-position insert
 * tie-break, and must match what the server uses.
 * ============================================================ */

/** A DELETE of length 0 — the annulled form of an insert swallowed by a delete. */
export function isNoop(op) {
  return op.operationType === 'DELETE' && op.length === 0;
}

/**
 * Transforms op1 against concurrent op2, returning the form of op1 that
 * applies after op2 has been applied.
 */
export function transformOp(op1, op2) {
  if (isNoop(op1) || isNoop(op2)) return { ...op1 };
  if (op1.operationType === 'INSERT') {
    return op2.operationType === 'INSERT'
      ? transformInsertInsert(op1, op2)
      : transformInsertDelete(op1, op2);
  }
  return op2.operationType === 'INSERT'
    ? transformDeleteInsert(op1, op2)
    : transformDeleteDelete(op1, op2);
}

/** Same position: the lower author string keeps the left position. */
function transformInsertInsert(op1, op2) {
  if (op1.position < op2.position) return { ...op1 };
  if (op1.position > op2.position) return { ...op1, position: op1.position + op2.text.length };
  return op1.author < op2.author
    ? { ...op1 }
    : { ...op1, position: op1.position + op2.text.length };
}

/**
 * An insert strictly inside the delete range is annulled, because
 * transformDeleteInsert expands the delete to swallow the inserted text when
 * applied in the opposite order. Repositioning instead of annulling breaks TP1.
 */
function transformInsertDelete(ins, del) {
  if (ins.position <= del.position) return { ...ins };
  if (ins.position >= del.position + del.length) return { ...ins, position: ins.position - del.length };
  return { operationType: 'DELETE', position: del.position, length: 0, author: ins.author };
}

function transformDeleteInsert(del, ins) {
  const delEnd = del.position + del.length;
  if (delEnd <= ins.position) return { ...del };
  if (del.position >= ins.position) return { ...del, position: del.position + ins.text.length };
  return { ...del, length: del.length + ins.text.length };
}

function transformDeleteDelete(op1, op2) {
  const op1End = op1.position + op1.length;
  const op2End = op2.position + op2.length;
  if (op1End <= op2.position) return { ...op1 };
  if (op1.position >= op2End) return { ...op1, position: op1.position - op2.length };
  const overlap = Math.min(op1End, op2End) - Math.max(op1.position, op2.position);
  const deletedBefore = Math.max(0, Math.min(op2End, op1.position) - op2.position);
  return { ...op1, position: op1.position - deletedBefore, length: op1.length - overlap };
}

/** Applies an operation to a document string. */
export function applyOp(doc, op) {
  if (isNoop(op)) return doc;
  if (op.operationType === 'INSERT') {
    return doc.slice(0, op.position) + op.text + doc.slice(op.position);
  }
  return doc.slice(0, op.position) + doc.slice(op.position + op.length);
}

/** Shifts a caret offset through an operation. */
export function transformCaret(caret, op) {
  if (isNoop(op)) return caret;
  if (op.operationType === 'INSERT') {
    return op.position <= caret ? caret + op.text.length : caret;
  }
  if (caret <= op.position) return caret;
  return Math.max(op.position, caret - op.length);
}

/**
 * Rebases a list of pending local operations against an incoming remote
 * operation, and the remote operation against the pending ones.
 *
 * This is the same pairwise walk the server performs when it transforms a
 * submission against the canonical operations that landed after its base
 * revision. Returns the rebased pending list and the remote operation in the
 * form that applies to the local document.
 */
export function rebase(pending, remoteOperation) {
  let remote = remoteOperation;
  const rebased = [];
  for (const op of pending) {
    rebased.push(transformOp(op, remote));
    remote = transformOp(remote, op);
  }
  return { pending: rebased, remote };
}
