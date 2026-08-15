// @ts-check
const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

/// The front-end's counterpart to the back-end's Error Prone gate (docs/quality-bar.md, #10).
/// `npm run lint` must pass before a ticket is closed, and CI gates on it.
module.exports = tseslint.config(
  {
    ignores: ['dist/**', '.angular/**', 'coverage/**', 'node_modules/**', 'prototypes/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...tseslint.configs.stylistic,
      ...angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'app', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'app', style: 'kebab-case' },
      ],
    },
  },
  {
    // The end-to-end harness (`e2e/*.mjs`, #64). Without this block those files are *matched* and
    // linted with **zero rules** — `eslint .` exits 0 over a reassigned `const` and a call to a
    // function that does not exist. That is this repo's signature defect: a gate reporting success
    // over code it never inspected (quality-bar.md §2's `-Xplugin`, ci.md §3's canary).
    files: ['**/*.mjs'],
    languageOptions: {
      // Listed rather than pulled from a `globals` package: these four are all the harness uses,
      // and `no-undef` is the rule that just caught a real one.
      globals: {
        console: 'readonly',
        fetch: 'readonly',
        process: 'readonly',
        URL: 'readonly',
      },
    },
    extends: [eslint.configs.recommended],
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {},
  },
);
