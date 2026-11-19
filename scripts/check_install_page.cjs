#!/usr/bin/env node
/*
 * Checks for the public installer page (docs/public/install/).
 *
 * The page is a static artefact: nothing compiles it and no runtime test would
 * ever open it. These checks are the only guard against the failure modes that
 * matter there — a version parser that orders releases wrong, an asset picker
 * that offers the gplay APK to standard users (different applicationId), an
 * invite code that accepts garbage, or a translation key that exists in one
 * language and silently falls back in the other.
 *
 * Run: node scripts/check_install_page.cjs
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const PAGE_DIR = path.join(__dirname, '..', 'docs', 'public', 'install');

// docs/package.json declares "type": "module", so require() would treat the
// browser script as ESM and return an empty namespace. Evaluate it the way the
// page does instead: as a classic script whose UMD wrapper sees no `module` and
// attaches SHWCore to the global object.
vm.runInThisContext(fs.readFileSync(path.join(PAGE_DIR, 'install-core.js'), 'utf8'), {
  filename: 'install-core.js'
});
const core = globalThis.SHWCore;
delete globalThis.SHWCore;

const html = fs.readFileSync(path.join(PAGE_DIR, 'index.html'), 'utf8');
const app = fs.readFileSync(path.join(PAGE_DIR, 'install-app.js'), 'utf8');
const css = fs.readFileSync(path.join(PAGE_DIR, 'install.css'), 'utf8');

const results = [];
function test(name, fn) {
  try {
    fn();
    results.push({ name, ok: true });
  } catch (error) {
    results.push({ name, ok: false, error: error.message });
  }
}

/* ------------------------------------------------------------------ versions */

test('parseVersion strips a leading v and reads numeric parts', () => {
  assert.deepStrictEqual(core.parseVersion('v2.12.1'), [2, 12, 1]);
  assert.deepStrictEqual(core.parseVersion('2.12'), [2, 12]);
  assert.deepStrictEqual(core.parseVersion(''), []);
  assert.deepStrictEqual(core.parseVersion(null), []);
});

test('compareVersions orders patch, minor and major correctly', () => {
  assert.strictEqual(core.compareVersions('2.12.1', '2.12.0'), 1);
  assert.strictEqual(core.compareVersions('2.12.0', '2.12.1'), -1);
  assert.strictEqual(core.compareVersions('2.13.0', '2.12.9'), 1);
  assert.strictEqual(core.compareVersions('3.0.0', '2.99.99'), 1);
  assert.strictEqual(core.compareVersions('2.12', '2.12.0'), 0);
  assert.strictEqual(core.compareVersions('v2.12.1', '2.12.1'), 0);
});

test('compareVersions does not treat 2.12.10 as older than 2.12.9', () => {
  assert.strictEqual(core.compareVersions('2.12.10', '2.12.9'), 1);
  assert.strictEqual(core.isNewer('2.12.10', '2.12.9'), true);
});

test('versionFromTag and assetVersion agree with the real release naming', () => {
  assert.strictEqual(core.versionFromTag('v2.12.1'), '2.12.1');
  assert.strictEqual(core.versionFromTag('release-2.12.1'), '');
  assert.strictEqual(core.assetVersion('Sherwood-WTA-2.12.1.apk'), '2.12.1');
  assert.strictEqual(core.assetVersion('Sherwood-WTA-2.12.1-gplay.apk'), '2.12.1');
  assert.strictEqual(core.assetVersion('SHA256SUMS.txt'), '');
});

/* -------------------------------------------------------------------- assets */

test('pickAssets separates standard, gplay, aab and checksums', () => {
  const picked = core.pickAssets([
    { name: 'Sherwood-WTA-2.12.1.apk', browser_download_url: 'https://x/s.apk', size: 27169552 },
    { name: 'Sherwood-WTA-2.12.1-gplay.apk', browser_download_url: 'https://x/g.apk', size: 27169197 },
    { name: 'Sherwood-WTA-2.12.1-gplay.aab', browser_download_url: 'https://x/g.aab', size: 42811945 },
    { name: 'SHA256SUMS.txt', browser_download_url: 'https://x/sums', size: 300 }
  ]);
  assert.strictEqual(picked.standard.url, 'https://x/s.apk');
  assert.strictEqual(picked.gplay.url, 'https://x/g.apk');
  assert.strictEqual(picked.aab.url, 'https://x/g.aab');
  assert.strictEqual(picked.sums.url, 'https://x/sums');
  assert.strictEqual(picked.standard.size, 27169552);
});

test('pickAssets never promotes a gplay apk to the standard slot', () => {
  const picked = core.pickAssets([
    { name: 'Sherwood-WTA-2.12.1-gplay.apk', browser_download_url: 'https://x/g.apk' }
  ]);
  assert.strictEqual(picked.standard, null);
  assert.strictEqual(picked.gplay.url, 'https://x/g.apk');
});

test('pickAssets tolerates null, missing urls and junk entries', () => {
  assert.deepStrictEqual(core.pickAssets(null), {
    standard: null, gplay: null, aab: null, sums: null
  });
  const picked = core.pickAssets([null, 3, { name: 'x.apk' }, { name: 'y.apk', url: 'https://x/y.apk' }]);
  assert.strictEqual(picked.standard.url, 'https://x/y.apk');
});

/* ------------------------------------------------------------------ releases */

test('normalizeRelease survives a release without the fields we expect', () => {
  const release = core.normalizeRelease({});
  assert.strictEqual(release.version, '');
  assert.strictEqual(release.url, core.RELEASES_PAGE);
  assert.deepStrictEqual(release.assets, { standard: null, gplay: null, aab: null, sums: null });
  assert.doesNotThrow(() => core.normalizeRelease(null));
});

test('normalizeRelease derives the version from the asset name when the tag is odd', () => {
  const release = core.normalizeRelease({
    tag_name: 'nightly',
    assets: [{ name: 'Sherwood-WTA-2.13.0.apk', browser_download_url: 'https://x/a.apk' }]
  });
  assert.strictEqual(release.version, '2.13.0');
});

test('fallbackRelease points at real download paths', () => {
  const release = core.fallbackRelease();
  assert.strictEqual(release.version, core.FALLBACK_VERSION);
  assert.ok(release.assets.standard.url.includes('/releases/download/v' + core.FALLBACK_VERSION + '/'));
  assert.ok(release.assets.standard.url.endsWith('Sherwood-WTA-' + core.FALLBACK_VERSION + '.apk'));
  assert.ok(release.assets.gplay.url.endsWith('-gplay.apk'));
});

/* ------------------------------------------------------------- invite codes */

test('normalizeInviteCode accepts every way a human retypes a code', () => {
  assert.strictEqual(core.normalizeInviteCode(' shw-abc123 '), 'SHW-ABC123');
  assert.strictEqual(core.normalizeInviteCode('SHW_ABC123'), 'SHW-ABC123');
  assert.strictEqual(core.normalizeInviteCode('shw abc123'), 'SHW-ABC123');
  assert.strictEqual(core.normalizeInviteCode('abc123'), 'SHW-ABC123');
  assert.strictEqual(core.normalizeInviteCode('SHWABC123'), 'SHW-ABC123');
  assert.strictEqual(core.normalizeInviteCode(''), '');
  assert.strictEqual(core.normalizeInviteCode(null), '');
});

test('isValidInviteCode requires exactly six body characters', () => {
  assert.strictEqual(core.isValidInviteCode('SHW-ABC123'), true);
  assert.strictEqual(core.isValidInviteCode('SHW-ABC12'), false);
  assert.strictEqual(core.isValidInviteCode('SHW-ABC1234'), false);
  assert.strictEqual(core.isValidInviteCode('SHW-ABC-12'), false);
  assert.strictEqual(core.isValidInviteCode('SHW-!!!!!!'), false);
  assert.strictEqual(core.isValidInviteCode('hello'), false);
});

test('generateInviteCode is deterministic for given bytes and avoids confusable letters', () => {
  assert.strictEqual(core.generateInviteCode([0, 0, 0, 0, 0, 0]), 'SHW-AAAAAA');
  const code = core.generateInviteCode([0, 1, 2, 3, 4, 5]);
  assert.match(code, /^SHW-[A-Z0-9]{6}$/);
  assert.ok(!/[ILOU]/.test(code.slice(4)), 'alphabet must not contain I, L, O or U');
  assert.strictEqual(core.generateInviteCode(new Uint8Array([255, 255, 255, 255, 255, 255])).length, 10);
});

test('buildInviteLink sets ref and preserves unrelated query parameters', () => {
  assert.strictEqual(
    core.buildInviteLink('https://example.dev/install/', 'SHW-ABC123'),
    'https://example.dev/install/?ref=SHW-ABC123'
  );
  const url = new URL(core.buildInviteLink('https://example.dev/install/?ref=OLD&utm=x', 'SHW-ABC123'));
  assert.strictEqual(url.searchParams.get('ref'), 'SHW-ABC123');
  assert.strictEqual(url.searchParams.get('utm'), 'x');
  assert.strictEqual(core.buildInviteLink('not a url', 'SHW-ABC123'), 'not a url');
});

test('readInviteRef extracts and normalizes the ref parameter', () => {
  assert.strictEqual(core.readInviteRef('?ref=shw-abc123'), 'SHW-ABC123');
  assert.strictEqual(core.readInviteRef('?ref=nope'), 'SHW-NOPE');
  assert.strictEqual(core.isValidInviteCode(core.readInviteRef('?ref=nope')), false);
  assert.strictEqual(core.readInviteRef(''), '');
});

/* ------------------------------------------------------------------- format */

test('formatBytes matches the size the release API reports', () => {
  assert.strictEqual(core.formatBytes(27169552), '26 MB');
  assert.strictEqual(core.formatBytes(0), '');
  assert.strictEqual(core.formatBytes(-1), '');
  assert.strictEqual(core.formatBytes('x'), '');
});

/* -------------------------------------------------------------------- i18n */

test('every language defines exactly the same keys', () => {
  const base = Object.keys(core.STRINGS.en).sort();
  core.LANGS.forEach((lang) => {
    assert.ok(core.STRINGS[lang], 'missing language table: ' + lang);
    const keys = Object.keys(core.STRINGS[lang]).sort();
    assert.deepStrictEqual(keys, base, 'key drift in ' + lang);
  });
});

test('no translation is empty', () => {
  core.LANGS.forEach((lang) => {
    Object.keys(core.STRINGS[lang]).forEach((key) => {
      const value = core.STRINGS[lang][key];
      assert.strictEqual(typeof value, 'string', lang + '.' + key + ' is not a string');
      assert.ok(value.trim().length > 0, lang + '.' + key + ' is empty');
    });
  });
});

test('every data-i18n key in the page exists in every language', () => {
  const keys = new Set();
  const re = /data-i18n(?:-placeholder)?="([^"]+)"/g;
  let match;
  while ((match = re.exec(html)) !== null) keys.add(match[1]);
  assert.ok(keys.size > 20, 'expected a substantial number of translated keys');
  keys.forEach((key) => {
    core.LANGS.forEach((lang) => {
      assert.ok(
        Object.prototype.hasOwnProperty.call(core.STRINGS[lang], key),
        'HTML uses "' + key + '" which is missing from ' + lang
      );
    });
  });
});

test('every string referenced from the page script exists', () => {
  const used = new Set();
  // \b keeps createElement('textarea') from being read as t('textarea').
  const re = /\b(?:t|format|setStatus)\('([A-Za-z0-9]+)'/g;
  let match;
  while ((match = re.exec(app)) !== null) used.add(match[1]);
  used.forEach((key) => {
    assert.ok(
      Object.prototype.hasOwnProperty.call(core.STRINGS.en, key),
      'page script uses "' + key + '" which is missing from STRINGS.en'
    );
  });
});

test('every element the script looks up exists in the page', () => {
  const ids = new Set();
  const re = /byId\('([A-Za-z0-9_-]+)'\)/g;
  let match;
  while ((match = re.exec(app)) !== null) ids.add(match[1]);
  ids.forEach((id) => {
    assert.ok(html.indexOf('id="' + id + '"') !== -1, 'page has no element with id "' + id + '"');
  });
});

/* -------------------------------------------------------------------- html */

test('page is self-contained and references the files that exist', () => {
  assert.ok(html.indexOf('install.css') !== -1);
  assert.ok(html.indexOf('install-core.js') !== -1);
  assert.ok(html.indexOf('install-app.js') !== -1);
  assert.ok(css.length > 2000, 'stylesheet looks truncated');
  assert.ok(app.length > 2000, 'page script looks truncated');
});

test('page does not use template delimiters that a docs build could eat', () => {
  assert.strictEqual(html.indexOf('{{'), -1, 'found {{ in the page markup');
  assert.strictEqual(app.indexOf('{{'), -1, 'found {{ in the page script');
});

test('page loads no third-party script or stylesheet', () => {
  assert.strictEqual(/<script[^>]+src="https?:/i.test(html), false, 'external script found');
  const externalStyles = /<link[^>]+rel="stylesheet"[^>]+href="https?:[^"]*"/i.test(html) ||
    /<link[^>]+href="https?:[^"]*"[^>]+rel="stylesheet"/i.test(html);
  assert.strictEqual(externalStyles, false, 'external stylesheet found');
  assert.strictEqual(/@import\s+url\(\s*["']?https?:/i.test(css), false, 'stylesheet imports a remote file');
});

/* ------------------------------------------------------------------ report */

const failed = results.filter((r) => !r.ok);
results.forEach((r) => {
  console.log((r.ok ? 'ok   ' : 'FAIL ') + r.name);
  if (!r.ok) console.log('       ' + r.error);
});
console.log('');
console.log(passedLine(results.length - failed.length, results.length));
if (failed.length) process.exit(1);

function passedLine(passed, total) {
  return passed + '/' + total + ' checks passed';
}
