/* ============================================================
 * CodeMirror 6 integration.
 *
 * Two responsibilities:
 *   1. Build the editor and translate its change events into the
 *      INSERT / DELETE operations the collaboration protocol speaks.
 *   2. Render remote participants' carets and selections from the
 *      presence protocol.
 *
 * Locally originated changes must be told apart from changes applied
 * on behalf of the server, or the client would echo the server's own
 * operations back to it. Remote dispatches carry the `remoteChange`
 * annotation and are skipped by the change listener.
 * ============================================================ */

import {
  EditorState, StateEffect, StateField, RangeSetBuilder, Compartment, Annotation,
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  drawSelection, rectangularSelection, dropCursor, highlightSpecialChars,
  Decoration, WidgetType,
  defaultKeymap, history, historyKeymap, indentWithTab,
  syntaxHighlighting, HighlightStyle, indentUnit, bracketMatching,
  tags, python, java, closeBrackets, closeBracketsKeymap,
} from './vendor/codemirror.bundle.js';

/* ---------------- Theme ---------------- */

/* Syntax colours are drawn from the same small palette as the rest of the
 * interface, so the editor reads as part of the product rather than as a
 * third-party widget dropped into it. */
const highlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: '#c084fc' },
  { tag: [tags.controlKeyword, tags.moduleKeyword], color: '#c084fc' },
  { tag: [tags.name, tags.deleted, tags.character, tags.macroName], color: '#e8edf7' },
  { tag: [tags.function(tags.variableName), tags.labelName], color: '#5b8cff' },
  { tag: [tags.definition(tags.name), tags.separator], color: '#e8edf7' },
  { tag: [tags.typeName, tags.className, tags.namespace], color: '#4dd4c0' },
  { tag: [tags.number, tags.bool, tags.null], color: '#ffb454' },
  { tag: [tags.string, tags.special(tags.string)], color: '#3ddc84' },
  { tag: [tags.operator, tags.operatorKeyword], color: '#93a0b8' },
  { tag: [tags.meta, tags.comment], color: '#5f6b85', fontStyle: 'italic' },
  { tag: tags.propertyName, color: '#8ab4ff' },
  { tag: tags.self, color: '#ff8fa3' },
  { tag: tags.invalid, color: '#ff5f7a' },
]);

const editorTheme = EditorView.theme({
  '&': {
    height: '100%',
    fontSize: '13.5px',
    backgroundColor: 'var(--bg)',
    color: 'var(--text)',
  },
  '.cm-scroller': {
    fontFamily: 'var(--mono)',
    lineHeight: '1.65',
    overflow: 'auto',
  },
  '.cm-content': { padding: '16px 0', caretColor: 'var(--accent)' },
  '.cm-gutters': {
    backgroundColor: 'var(--bg)',
    color: 'var(--text-faint)',
    border: 'none',
    paddingRight: '6px',
    userSelect: 'none',
  },
  '.cm-lineNumbers .cm-gutterElement': { padding: '0 8px 0 20px', minWidth: '44px' },
  '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--text-dim)' },
  '.cm-activeLine': { backgroundColor: 'rgba(255,255,255,0.026)' },
  '&.cm-focused .cm-cursor': { borderLeftColor: 'var(--accent)', borderLeftWidth: '2px' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection': {
    backgroundColor: 'rgba(91,140,255,0.26)',
  },
  '.cm-matchingBracket, &.cm-focused .cm-matchingBracket': {
    backgroundColor: 'rgba(91,140,255,0.22)',
    outline: '1px solid rgba(91,140,255,0.5)',
    color: 'inherit',
  },
  '.cm-specialChar': { color: 'var(--text-faint)' },
}, { dark: true });

/* ---------------- Remote presence decorations ---------------- */

/** Marks a transaction as applied on behalf of the server, not the user. */
export const remoteChange = Annotation.define();

/** Replaces the whole remote-presence set. Payload: array of participant presences. */
const setPresence = StateEffect.define();

class CaretWidget extends WidgetType {
  constructor(name, color) {
    super();
    this.name = name;
    this.color = color;
  }

  // Widgets are recreated on every presence update; without this CodeMirror
  // would tear down and rebuild every caret on each cursor move, which makes
  // the name labels flicker.
  eq(other) {
    return other.name === this.name && other.color === this.color;
  }

  toDOM() {
    const wrap = document.createElement('span');
    wrap.className = 'cm-remote-caret';
    wrap.style.setProperty('--caret-color', this.color);
    // The caret is decoration, not document content: hidden from assistive
    // technology and from text selection so it cannot leak into a copy.
    wrap.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.className = 'cm-remote-caret-label';
    label.textContent = this.name;
    wrap.appendChild(label);
    return wrap;
  }

  ignoreEvent() {
    return true;
  }
}

/* Presence positions are absolute document offsets. They are mapped through
 * every local change so a remote caret stays anchored to its text between
 * presence broadcasts instead of drifting as this user types above it. */
const presenceField = StateField.define({
  create() {
    return [];
  },
  update(presences, tr) {
    for (const effect of tr.effects) {
      if (effect.is(setPresence)) {
        return clampAll(effect.value, tr.state.doc.length);
      }
    }
    if (!tr.docChanged) return presences;
    return presences.map((p) => ({
      ...p,
      from: tr.changes.mapPos(p.from, 1),
      to: tr.changes.mapPos(p.to, -1),
    }));
  },
  provide: (field) => EditorView.decorations.from(field, buildDecorations),
});

function clampAll(presences, docLength) {
  return presences.map((p) => ({
    ...p,
    from: Math.max(0, Math.min(p.from, docLength)),
    to: Math.max(0, Math.min(p.to, docLength)),
  }));
}

function buildDecorations(presences) {
  const builder = new RangeSetBuilder();
  // RangeSetBuilder requires ascending order; selections are emitted before
  // the caret widget at the same position so ranges never interleave.
  const decorations = [];
  for (const p of presences) {
    const from = Math.min(p.from, p.to);
    const to = Math.max(p.from, p.to);
    if (to > from) {
      decorations.push({
        from,
        to,
        deco: Decoration.mark({
          class: 'cm-remote-selection',
          attributes: { style: `--selection-color:${p.color}` },
        }),
      });
    }
    decorations.push({
      from: p.to,
      to: p.to,
      deco: Decoration.widget({ widget: new CaretWidget(p.name, p.color), side: 1 }),
    });
  }
  decorations.sort((a, b) => a.from - b.from || a.to - b.to);
  for (const d of decorations) builder.add(d.from, d.to, d.deco);
  return builder.finish();
}

/* ---------------- Language ---------------- */

const languageCompartment = new Compartment();
const editableCompartment = new Compartment();

function languageSupport(language) {
  return String(language).toUpperCase() === 'JAVA' ? java() : python();
}

/* ---------------- Editor construction ---------------- */

/**
 * Creates the collaborative editor.
 *
 * @param options.parent       host element
 * @param options.language     "PYTHON" or "JAVA"
 * @param options.onOperations called with the INSERT/DELETE operations produced
 *                             by a local edit, in document order
 * @param options.onSelection  called with {start, end} when the local caret moves
 * @returns a small facade over the EditorView
 */
export function createEditor({ parent, language, onOperations, onSelection }) {
  const changeListener = EditorView.updateListener.of((update) => {
    if (update.docChanged) {
      const operations = [];
      for (const tr of update.transactions) {
        if (tr.annotation(remoteChange)) continue;
        collectOperations(tr, operations);
      }
      if (operations.length) onOperations(operations);
    }
    if (update.selectionSet || update.docChanged) {
      const range = update.state.selection.main;
      onSelection({ start: range.from, end: range.to });
    }
  });

  const view = new EditorView({
    parent,
    state: EditorState.create({
      doc: '',
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        highlightSpecialChars(),
        history(),
        drawSelection(),
        dropCursor(),
        rectangularSelection(),
        bracketMatching(),
        closeBrackets(),
        indentUnit.of('    '),
        keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, indentWithTab]),
        languageCompartment.of(languageSupport(language)),
        // Editing is disabled until document_sync arrives, so a participant
        // cannot type into a document the server has not sent yet.
        editableCompartment.of(EditorView.editable.of(false)),
        syntaxHighlighting(highlightStyle),
        presenceField,
        editorTheme,
        EditorView.lineWrapping,
        changeListener,
      ],
    }),
  });

  return {
    view,

    get document() {
      return view.state.doc.toString();
    },

    get selection() {
      const range = view.state.selection.main;
      return { start: range.from, end: range.to };
    },

    focus: () => view.focus(),

    setEditable(editable) {
      view.dispatch({
        effects: editableCompartment.reconfigure(EditorView.editable.of(editable)),
      });
      view.dom.classList.toggle('is-readonly', !editable);
    },

    setLanguage(next) {
      view.dispatch({ effects: languageCompartment.reconfigure(languageSupport(next)) });
    },

    /** Replaces the whole document — used for document_sync and resync. */
    replaceDocument(text) {
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: text },
        annotations: remoteChange.of(true),
      });
    },

    /** Applies a canonical remote operation without re-emitting it. */
    applyRemoteOperation(op) {
      if (op.operationType === 'INSERT') {
        view.dispatch({
          changes: { from: op.position, insert: op.text },
          annotations: remoteChange.of(true),
        });
      } else if (op.length > 0) {
        view.dispatch({
          changes: { from: op.position, to: op.position + op.length },
          annotations: remoteChange.of(true),
        });
      }
    },

    setRemotePresence(presences) {
      view.dispatch({ effects: setPresence.of(presences) });
    },

    destroy: () => view.destroy(),
  };
}

/**
 * Translates a CodeMirror transaction into protocol operations.
 *
 * CodeMirror reports a replacement as one change with both a removed range and
 * inserted text; the protocol has no replace, so it becomes a DELETE followed
 * by an INSERT at the same position. Offsets are read from the document *before*
 * the transaction, and changes are visited in ascending order, so each operation
 * is expressed against the state the previous one produced.
 */
function collectOperations(tr, out) {
  let drift = 0;
  tr.changes.iterChanges((fromA, toA, fromB, toB, inserted) => {
    const position = fromA + drift;
    const removed = toA - fromA;
    const text = inserted.toString();
    if (removed > 0) out.push({ operationType: 'DELETE', position, length: removed });
    if (text.length > 0) out.push({ operationType: 'INSERT', position, text });
    drift += text.length - removed;
  });
}
