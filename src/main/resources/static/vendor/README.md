# Vendored third-party assets

`codemirror.bundle.js` is a pre-built ES module bundle of CodeMirror 6. It is
committed so the client stays zero-build: the browser imports it directly and
the repository has no npm install, bundler, or CDN dependency at run time.

## Contents

| Package | Version |
|---|---|
| `@codemirror/state` | 6.7.1 |
| `@codemirror/view` | 6.43.9 |
| `@codemirror/commands` | 6.11.0 |
| `@codemirror/language` | 6.12.4 |
| `@codemirror/lang-python` | 6.2.1 |
| `@codemirror/lang-java` | 6.0.2 |
| `@codemirror/autocomplete` | 6.20.3 (bracket closing only) |
| `@lezer/highlight` | 1.2.3 |

Licensed MIT. Copyright (c) by Marijn Haverbeke and others —
see https://github.com/codemirror/dev.

## Regenerating

`codemirror.entry.js` is the bundle entry point and lists the exact exports the
client uses. To rebuild after changing it, in a scratch directory:

```bash
npm install codemirror@6 @codemirror/state@6 @codemirror/view@6 \
  @codemirror/commands@6 @codemirror/language@6 @codemirror/lang-python@6 \
  @codemirror/lang-java@6 @codemirror/autocomplete@6 @lezer/highlight@1 esbuild
npx esbuild codemirror.entry.js --bundle --format=esm --minify \
  --legal-comments=none --target=es2020 --outfile=codemirror.bundle.js
```

This is an authoring-time step only. Nothing in the normal build, test, or run
path invokes it.
