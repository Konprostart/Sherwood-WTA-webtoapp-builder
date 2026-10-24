# Plugin Market

This directory **is** the Sherwood WTA Plugin Market. Every plugin the
in-app market shows is fetched directly from this folder over
`raw.githubusercontent.com`, with `cdn.jsdelivr.net/gh/` as a CDN fallback.
There is no other backend. A merged PR is published the moment it lands on
`main`.

This file is the canonical contribution guide. The root `README.md` and
`.github/CONTRIBUTING.md` only keep high-level summaries and link here for
the actual submission rules.

> **English**

---

## At a glance

```
modules/
├── registry.json              ← index file the app downloads first
├── submissions.json           ← CI-generated PR / contributor metadata
├── README.md                  ← this file
└── <plugin-path>/             ← one folder per plugin
    ├── plugin.json            ← plugin manifest (required)
    ├── plugin.html            ← the whole plugin (required) — see below
    └── icon.png               ← optional 256 KB-max icon (also .svg/.webp/.jpg)
```

A plugin is **one HTML document** — no custom DSL. `plugin.html` carries both
sides of the plugin:

```html
<script type="hcj/page">
  // page-side code — runs inside matching pages like a userscript
  hcj.on('action', () => { /* user tapped the entry */ });
  hcj.addStyle('img { border-radius: 8px; }');   // inject page CSS from JS
</script>

<!-- everything else in the file is the panel UI -->
<style>body { font-family: sans-serif; padding: 16px; }</style>
<button id="go">Go</button>
<script>
  document.getElementById('go').onclick = () => hcjPanel.send({ go: true });
</script>
```

The `<script type="hcj/page">` block never executes inside the panel (unknown
script types are inert — the standard HTML data-block idiom); the host
extracts it and injects it into matching pages. The rest of the document is
the plugin's own interface, hosted in a bottom sheet / floating window /
fullscreen — whichever the user picked. A plugin with only the `hcj/page`
block has no panel.

> **Legacy layout:** packages made of `main.js` + `style.css` + `panel.html`
> still install and validate, but new submissions should ship `plugin.html`.

When a user opens the market, the app fetches both `registry.json` and
`submissions.json`, then renders each entry that appears in **both** —
that's how we guarantee the catalog only shows plugins whose PR has
actually been merged. Tapping **Install** downloads the package files and
hands them to the plugin store. The registry is cached for one hour.

> **Compatibility:** the catalog is `plugin.json`-only. The retired
> self-developed `module.json` format was removed from the market together
> with its old submissions — CI rejects it.

---

## Submitting a plugin

1. **Fork** [`Konprostart/Sherwood-WTA-webtoapp-builder`](https://github.com/Konprostart/Sherwood-WTA-webtoapp-builder).
2. Create a unique kebab-case folder under `modules/`, e.g.
   `modules/dark-reader-lite/`. The folder name is your `path` in
   `registry.json`.
3. Add at minimum:
   - `plugin.json` — manifest, see [schema](#pluginjson-schema)
   - `plugin.html` — the plugin document (page script + panel UI)
4. Add a matching entry to [`registry.json`](registry.json). Keep `id`,
   `name`, and `version` consistent between both files.
5. Open a pull request. CI validates the catalog — see
   [Local validation](#local-validation) to self-check first.
6. Once merged, every client picks up the new plugin on its next refresh.

There is no separate developer account, no API key, no submission portal.

---

## `plugin.json` schema

```json
{
  "id": "globally-unique-id",
  "name": "Display Name",
  "version": "1.0.0",
  "description": "Paragraph shown on the install page.",
  "author": "your name",
  "homepage": "https://…",
  "icon": "auto_awesome",
  "matches": ["*://*.example.com/*"],
  "excludeMatches": [],
  "runAt": "document_end",
  "permissions": ["STORAGE"],
  "toolbar": true
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `id` | yes | Globally unique; never reuse. |
| `name` | yes | Display name. |
| `version` | | Semver string; bump it for updates. |
| `description` | | Shown in the market listing. |
| `author` / `homepage` | | Credits. |
| `icon` | | [Material Icons](https://fonts.google.com/icons) name, or an icon file in the package. For a branded picture prefer the registry-level `iconUrl`. |
| `matches` | | Chrome-style match patterns; `/regex/` denotes a regex (Tampermonkey `@include` convention). Default `["*"]`. |
| `excludeMatches` | | Same syntax; wins over `matches`. |
| `runAt` | | `document_start` · `document_end` (default) · `document_idle`. |
| `permissions` | | Capabilities used from `hcj.*`: `STORAGE` (config KV), `FETCH` (cross-origin), `NOTIFY`, `BADGE`, `CLIPBOARD`, `DOWNLOAD`. Page DOM access needs no permission. |
| `toolbar` | | Show an entry for this plugin in the plugin surface. Default `true`. |

## `registry.json` entry schema

Unchanged: `id`, `path`, `name`, `description`, `icon`, `category`, `tags`,
`version`, `minAppVersion`, `author`, `runAt`, `permissions`, `urlMatches`,
`hasCss`, optional `iconUrl`, `sourceType` (`CUSTOM` / `CHROME_EXTENSION` +
`storeId` for Chrome Web Store passthrough entries).

## `plugin.html` runtime contract

The `hcj/page` script executes inside the page with a scoped `hcj` API object:

```js
hcj.id                    // plugin id
hcj.manifest              // {id, name, version, icon, hasPanel}
hcj.lang                  // app language code

hcj.config.get(key, def)  // persistent KV (requires STORAGE permission)
hcj.config.set(key, val)
hcj.config.remove(key)
hcj.config.all()

hcj.fetch(url, opts)      // cross-origin fetch (requires FETCH) → Promise
hcj.notify(title, body)   // Android notification (requires NOTIFY)
hcj.badge(text, color)    // toolbar badge (requires BADGE)

hcj.panel.open()          // open this plugin's panel
hcj.panel.close()
hcj.panel.send(msg)       // page → panel message
hcj.panel.onMessage(fn)   // panel → page messages

hcj.on('action', fn)      // fired when the user taps the plugin entry
                          // (plugins without panel UI)
hcj.emit(evt, data)       // fire a custom event into your own handlers
hcj.addStyle(css)         // inject a <style> into the page (idempotent)
```

Inside the panel document, a mirrored `hcjPanel` object is available:
`hcjPanel.config.*`, `hcjPanel.send(msg)`, `hcjPanel.onMessage(fn)`,
`hcjPanel.close()`.

Settings UI is *yours*: build it in the same `plugin.html` with ordinary
HTML/CSS/JS and persist via `hcjPanel.config`. There is no form DSL to learn.

## Versioning

Bump `version` in both `plugin.json` and `registry.json`. Installed clients
see **Update available** when the registry version is newer than the local
one.

## Local validation

```bash
python3 .github/scripts/ci/validate_modules.py
```

## Reviewer checklist

- `plugin.json` parses and `id`/`name`/`version` match `registry.json`.
- The page script (`hcj/page` block) has no top-level `return`, no obfuscation, no remote-code fetch+eval.
- `permissions` only lists what the code actually calls.
- Icons stay under 256 KB; `iconUrl` relative paths point at allowed filenames.
