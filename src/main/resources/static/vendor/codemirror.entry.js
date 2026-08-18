// Vendored CodeMirror 6 bundle entry point for the Collaborative Code Editor.
// Re-exports exactly the surface the client uses, so the bundle stays small.
export { EditorState, StateEffect, StateField, RangeSetBuilder, Compartment, Annotation } from '@codemirror/state';
export {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  drawSelection, rectangularSelection, crosshairCursor, dropCursor,
  highlightSpecialChars, Decoration, WidgetType, ViewPlugin,
} from '@codemirror/view';
export { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
export {
  syntaxHighlighting, HighlightStyle, indentUnit, bracketMatching,
  foldGutter, foldKeymap,
} from '@codemirror/language';
export { tags } from '@lezer/highlight';
export { python } from '@codemirror/lang-python';
export { java } from '@codemirror/lang-java';
export { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
