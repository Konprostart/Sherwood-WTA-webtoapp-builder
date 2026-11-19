/*
 * Sherwood Studio installer — shared, dependency-free helpers.
 *
 * Loaded as a classic script in the browser (exposes window.SHWCore) and
 * required directly by scripts/check_install_page.cjs in Node, so the rules
 * that matter (version compare, invite codes, asset selection, strings) are
 * covered by tests instead of only being eyeballed in a browser.
 *
 * Nothing here touches the DOM and nothing here talks to the network.
 */
(function (root, factory) {
  'use strict';
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.SHWCore = factory();
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  var REPO = 'Konprostart/Sherwood-WTA-webtoapp-builder';
  var REPO_URL = 'https://github.com/' + REPO;
  var RELEASES_PAGE = REPO_URL + '/releases';
  var API_LATEST = 'https://api.github.com/repos/' + REPO + '/releases/latest';
  // Used when the API is rate-limited or the visitor is offline. The page is
  // deployed with the repo, so this only has to be right on the day it ships.
  var PUBLIC_URL = 'https://konprostart.github.io/Sherwood-WTA-webtoapp-builder/install/';
  var FALLBACK_VERSION = '2.12.1';
  // GitHub allows 60 unauthenticated requests per hour per address; a cached
  // answer keeps a shared network well under that.
  var CACHE_TTL_MS = 6 * 60 * 60 * 1000;

  var LANGS = ['en', 'sr'];

  var INVITE_PREFIX = 'SHW-';
  var INVITE_BODY_LENGTH = 6;
  // Crockford-ish alphabet: no I, L, O, U, so a code read out loud or copied
  // by hand cannot turn into a different valid-looking code.
  var INVITE_ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';
  var INVITE_RE = /^SHW-[A-Z0-9]{6}$/;

  /* ------------------------------------------------------------------ *
   * Versions
   * ------------------------------------------------------------------ */

  function parseVersion(value) {
    var text = String(value == null ? '' : value).trim().toLowerCase();
    if (text.charAt(0) === 'v') text = text.slice(1);
    var core = text.split('-')[0].split('+')[0];
    if (!core) return [];
    var parts = core.split('.');
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var n = parseInt(parts[i], 10);
      if (!isFinite(n) || n < 0) return out;
      out.push(n);
    }
    return out;
  }

  function compareVersions(a, b) {
    var left = parseVersion(a);
    var right = parseVersion(b);
    var length = Math.max(left.length, right.length);
    for (var i = 0; i < length; i++) {
      var x = left[i] || 0;
      var y = right[i] || 0;
      if (x !== y) return x < y ? -1 : 1;
    }
    return 0;
  }

  function isNewer(candidate, current) {
    return compareVersions(candidate, current) > 0;
  }

  function versionFromTag(tag) {
    var text = String(tag == null ? '' : tag).trim();
    if (text.charAt(0) === 'v' || text.charAt(0) === 'V') text = text.slice(1);
    return /^\d+\.\d+\.\d+/.test(text) ? text : '';
  }

  function assetVersion(name) {
    // Only the numeric core: "Sherwood-WTA-2.12.1-gplay.apk" must yield
    // "2.12.1", not "2.12.1-gplay.apk", or the chip would show a filename.
    var match = /(\d+\.\d+\.\d+)/.exec(String(name == null ? '' : name));
    return match ? match[1] : '';
  }

  /* ------------------------------------------------------------------ *
   * Release parsing
   * ------------------------------------------------------------------ */

  function toAsset(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var name = typeof raw.name === 'string' ? raw.name : '';
    var url = typeof raw.browser_download_url === 'string' ? raw.browser_download_url
      : (typeof raw.url === 'string' ? raw.url : '');
    if (!name || !url) return null;
    return {
      name: name,
      url: url,
      size: typeof raw.size === 'number' && raw.size > 0 ? raw.size : 0
    };
  }

  // The two flavours ship different applicationIds (com.sherwood.wta vs
  // com.sherwood.wta.play), so offering the wrong file is not cosmetic: the
  // installer rejects it or installs a second app next to the first.
  function pickAssets(assets) {
    var picked = { standard: null, gplay: null, aab: null, sums: null };
    if (!Array.isArray(assets)) return picked;
    for (var i = 0; i < assets.length; i++) {
      var asset = toAsset(assets[i]);
      if (!asset) continue;
      var lower = asset.name.toLowerCase();
      if (/\.aab$/.test(lower)) {
        if (!picked.aab) picked.aab = asset;
      } else if (/sha256sums/i.test(lower)) {
        if (!picked.sums) picked.sums = asset;
      } else if (/\.apk$/.test(lower)) {
        if (lower.indexOf('gplay') !== -1 || lower.indexOf('play') !== -1) {
          if (!picked.gplay) picked.gplay = asset;
        } else if (!picked.standard) {
          picked.standard = asset;
        }
      }
    }
    return picked;
  }

  function normalizeRelease(raw) {
    var release = raw && typeof raw === 'object' ? raw : {};
    var assets = pickAssets(release.assets);
    var tag = typeof release.tag_name === 'string' ? release.tag_name : '';
    var version = versionFromTag(tag);
    if (!version && assets.standard) version = assetVersion(assets.standard.name);
    if (!version && assets.gplay) version = assetVersion(assets.gplay.name);
    return {
      tag: tag,
      version: version,
      name: typeof release.name === 'string' ? release.name : '',
      url: typeof release.html_url === 'string' && release.html_url ? release.html_url : RELEASES_PAGE,
      notes: typeof release.body === 'string' ? release.body : '',
      publishedAt: typeof release.published_at === 'string' ? release.published_at : '',
      assets: assets
    };
  }

  function fallbackRelease() {
    var version = FALLBACK_VERSION;
    var base = REPO_URL + '/releases/download/v' + version + '/';
    var apk = 'Sherwood-WTA-' + version + '.apk';
    var gplayApk = 'Sherwood-WTA-' + version + '-gplay.apk';
    var aab = 'Sherwood-WTA-' + version + '-gplay.aab';
    return {
      tag: 'v' + version,
      version: version,
      name: 'Sherwood Studio ' + version,
      url: REPO_URL + '/releases/tag/v' + version,
      notes: '',
      publishedAt: '',
      assets: {
        standard: { name: apk, url: base + apk, size: 0 },
        gplay: { name: gplayApk, url: base + gplayApk, size: 0 },
        aab: { name: aab, url: base + aab, size: 0 },
        sums: { name: 'SHA256SUMS.txt', url: base + 'SHA256SUMS.txt', size: 0 }
      }
    };
  }

  /* ------------------------------------------------------------------ *
   * Invite codes
   * ------------------------------------------------------------------ */

  function normalizeInviteCode(input) {
    if (input == null) return '';
    var text = String(input).trim().toUpperCase();
    if (!text) return '';
    // "shw abc123", "SHW_ABC123", "shw-abc123" and "abc123" all mean the same
    // thing to a person retyping a code from a screenshot.
    text = text.replace(/[\s_]+/g, '-');
    if (text.indexOf(INVITE_PREFIX) === 0) return text;
    if (text.indexOf('SHW') === 0) {
      var rest = text.slice(3).replace(/^-/, '');
      return rest ? INVITE_PREFIX + rest : '';
    }
    return INVITE_PREFIX + text;
  }

  function isValidInviteCode(input) {
    return INVITE_RE.test(normalizeInviteCode(input));
  }

  // Six characters from a caller-supplied byte array, so tests can pin the
  // output. Uses getRandomValues in the browser and Math.random only as a
  // last-resort fallback for ancient WebViews.
  function generateInviteCode(bytes) {
    var source = bytes && bytes.length ? bytes : null;
    var out = '';
    for (var i = 0; i < INVITE_BODY_LENGTH; i++) {
      var byte = source && i < source.length ? source[i] : Math.floor(Math.random() * 256);
      out += INVITE_ALPHABET.charAt(byte % INVITE_ALPHABET.length);
    }
    return INVITE_PREFIX + out;
  }

  function buildInviteLink(baseUrl, code) {
    var fallback = String(baseUrl == null ? '' : baseUrl);
    try {
      var url = new URL(fallback);
      url.searchParams.set('ref', code);
      url.hash = '';
      return url.toString();
    } catch (error) {
      return fallback;
    }
  }

  function readInviteRef(search) {
    try {
      var params = new URLSearchParams(String(search == null ? '' : search));
      return normalizeInviteCode(params.get('ref'));
    } catch (error) {
      return '';
    }
  }

  /* ------------------------------------------------------------------ *
   * Presentation helpers
   * ------------------------------------------------------------------ */

  function formatBytes(bytes) {
    var value = Number(bytes);
    if (!isFinite(value) || value <= 0) return '';
    var mb = value / (1024 * 1024);
    if (mb >= 10) return Math.round(mb) + ' MB';
    if (mb >= 1) return (Math.round(mb * 10) / 10) + ' MB';
    return Math.round(value / 1024) + ' KB';
  }

  var STRINGS = {
    en: {
      pageTitle: 'Install Sherwood Studio',
      metaDescription: 'Install Sherwood Studio — build real Android APKs on your phone. Download the official APK, share your invite link, and get ready for crypto rewards.',
      skipToContent: 'Skip to content',
      brand: 'Sherwood Studio',
      brandTag: 'Android APK workshop',
      footerSource: 'Source on GitHub',
      heroBadge: 'Public domain · open source',
      heroTitle: 'Install Sherwood Studio',
      heroSubtitle: 'Build real Android APKs on your phone. No PC, no remote build queue, no account.',
      versionLoading: 'Latest version',
      latestVersion: 'Version {v}',
      heroMeta: 'Android 7.0+ · Open source',
      download: 'Download APK',
      downloadGplay: 'Google Play build',
      downloadHint: 'Official build from GitHub Releases. The standard APK is what most phones need.',
      checksums: 'SHA-256 checksums',
      releaseNotes: 'Release notes',
      allReleases: 'All releases',
      stepsTitle: 'Three steps',
      step1Title: 'Download',
      step1Body: 'Tap “Download APK”. On a computer, open this page on your phone and tap again.',
      step2Title: 'Allow the install',
      step2Body: 'Android warns about unknown sources. That is expected for sideloaded builds — allow it for your browser once.',
      step3Title: 'Install and open',
      step3Body: 'Open the downloaded file, confirm, and launch. Updates install over the old version and keep your data.',
      unknownSources: 'Sherwood Studio is distributed outside Google Play, so the system installer asks for confirmation. Nothing else is required.',
      computerHint: 'You are on a computer — open this page on your Android phone to install. You can also copy the link below and send it to yourself.',
      inviteBadge: 'Soon',
      inviteTitle: 'Invite friends, earn crypto',
      inviteSubtitle: 'Share your link. When rewards go live, your invite code is what links the friends you brought to you.',
      yourCode: 'Your invite code',
      yourLink: 'Your invite link',
      copy: 'Copy',
      copied: 'Copied',
      share: 'Share with…',
      shareEmail: 'Email',
      shareSubject: 'Install Sherwood Studio',
      shareMessage: 'Install Sherwood Studio — build Android APKs on your phone. My invite code: {code}',
      shareCountLabel: 'Invites shared from this device: {n}',
      haveCode: 'Have an invite code?',
      codePlaceholder: 'SHW-XXXXXX',
      apply: 'Apply',
      applyOk: 'Invite code applied — {code}',
      applyInvalid: 'That code does not look right. Expected format: SHW-XXXXXX.',
      applySelf: 'That is your own code. Share it with a friend instead.',
      applyExists: 'You already stored an invite code on this device.',
      invitedBy: 'You were invited by {code}. The reward program is not live yet.',
      rewardsTitle: 'Reward tiers',
      rewardSoon: 'Soon',
      tier1: 'Invite 1 friend',
      tier5: 'Invite 5 friends',
      tier25: 'Invite 25 friends',
      fineprint: 'The reward program is not live yet. Your code, your invite and your share count are stored only in this browser — no account, no server, no analytics. When rewards launch, this page will show how to claim in crypto; until then every reward reads “Soon”.',
      faqTitle: 'Questions',
      faq1Q: 'Is it free?',
      faq1A: 'Yes. Sherwood Studio is public-domain open source; the full source is on GitHub.',
      faq2Q: 'Which file should I download?',
      faq2A: 'The standard APK — unless you specifically want the Play-ready build, which is the second button.',
      faq3Q: 'Do I lose my apps when I update?',
      faq3A: 'No. Every release since 2.9.0 is signed with the same key, so updates install over the previous version and keep your data.',
      footerTagline: 'Build Android APKs on your phone.',
      footerDocs: 'Documentation',
      footerLicence: 'Public domain',
      noTracking: 'No analytics, no cookies. This page only asks GitHub for the latest release.'
    },
    sr: {
      pageTitle: 'Instaliraj Sherwood Studio',
      metaDescription: 'Instaliraj Sherwood Studio — pravi prave Android APK-ove na telefonu. Preuzmi zvanični APK, podeli svoj invite link i pripremi se za kripto nagrade.',
      skipToContent: 'Pređi na sadržaj',
      brand: 'Sherwood Studio',
      brandTag: 'Android APK radionica',
      footerSource: 'Izvor na GitHub-u',
      heroBadge: 'Javno dobro · otvoren kod',
      heroTitle: 'Instaliraj Sherwood Studio',
      heroSubtitle: 'Pravi prave Android APK-ove na telefonu. Bez računara, bez reda za build, bez naloga.',
      versionLoading: 'Najnovija verzija',
      latestVersion: 'Verzija {v}',
      heroMeta: 'Android 7.0+ · otvoren kod',
      download: 'Preuzmi APK',
      downloadGplay: 'Google Play verzija',
      downloadHint: 'Zvanični build sa GitHub Releases. Standardni APK je ono što većini telefona treba.',
      checksums: 'SHA-256 kontrolne sume',
      releaseNotes: 'Beleške o izdanju',
      allReleases: 'Sva izdanja',
      stepsTitle: 'Tri koraka',
      step1Title: 'Preuzmi',
      step1Body: 'Dodirni „Preuzmi APK”. Ako si na računaru, otvori ovu stranu na telefonu i dodirni ponovo.',
      step2Title: 'Dozvoli instalaciju',
      step2Body: 'Android upozorava na nepoznate izvore. To je očekivano za build koji nije sa Play prodavnice — dozvoli to jednom za svoj browser.',
      step3Title: 'Instaliraj i otvori',
      step3Body: 'Otvori preuzeti fajl, potvrdi i pokreni. Nadogradnje se instaliraju preko stare verzije i čuvaju podatke.',
      unknownSources: 'Sherwood Studio se deli van Google Play-a, pa sistem traži potvrdu pri instalaciji. Ništa drugo nije potrebno.',
      computerHint: 'Na računaru si — otvori ovu stranu na Android telefonu da instaliraš. Link ispod možeš i da kopiraš i pošalješ sebi.',
      inviteBadge: 'Uskoro',
      inviteTitle: 'Pozovi prijatelje, zaradi kripto',
      inviteSubtitle: 'Podeli svoj link. Kada nagrade budu aktivne, tvoj invite kod povezuje prijatelje koje si doveo sa tobom.',
      yourCode: 'Tvoj invite kod',
      yourLink: 'Tvoj invite link',
      copy: 'Kopiraj',
      copied: 'Kopirano',
      share: 'Podeli sa…',
      shareEmail: 'Email',
      shareSubject: 'Instaliraj Sherwood Studio',
      shareMessage: 'Instaliraj Sherwood Studio — pravi Android APK-ove na telefonu. Moj invite kod: {code}',
      shareCountLabel: 'Podeljenih poziva sa ovog uređaja: {n}',
      haveCode: 'Imaš invite kod?',
      codePlaceholder: 'SHW-XXXXXX',
      apply: 'Primeni',
      applyOk: 'Invite kod primenjen — {code}',
      applyInvalid: 'Kod ne izgleda ispravno. Očekivani format: SHW-XXXXXX.',
      applySelf: 'To je tvoj kod. Podeli ga sa prijateljem.',
      applyExists: 'Na ovom uređaju je već sačuvan invite kod.',
      invitedBy: 'Pozvao te je {code}. Program nagrada još nije aktivan.',
      rewardsTitle: 'Nivoi nagrada',
      rewardSoon: 'Uskoro',
      tier1: 'Pozovi 1 prijatelja',
      tier5: 'Pozovi 5 prijatelja',
      tier25: 'Pozovi 25 prijatelja',
      fineprint: 'Program nagrada još nije aktivan. Tvoj kod, poziv i broj deljenja čuvaju se samo u ovom browseru — bez naloga, bez servera, bez analitike. Kada nagrade budu aktivne, strana će pokazati kako se preuzimaju u kriptu; do tada svaka nagrada piše „Uskoro”.',
      faqTitle: 'Pitanja',
      faq1Q: 'Da li je besplatno?',
      faq1A: 'Jeste. Sherwood Studio je otvoren kod u javnom dobru; ceo izvor je na GitHub-u.',
      faq2Q: 'Koji fajl da preuzmem?',
      faq2A: 'Standardni APK — osim ako baš želiš Play verziju, koja je na drugom dugmetu.',
      faq3Q: 'Da li gubim aplikacije pri nadogradnji?',
      faq3A: 'Ne. Svako izdanje od 2.9.0 potpisano je istim ključem, pa se nadogradnja instalira preko prethodne i čuva podatke.',
      footerTagline: 'Pravi Android APK-ove na telefonu.',
      footerDocs: 'Dokumentacija',
      footerLicence: 'Javno dobro',
      noTracking: 'Bez analitike i kolačića. Strana samo pita GitHub za najnovije izdanje.'
    }
  };

  function shareMessage(lang, code) {
    var table = STRINGS[lang] || STRINGS.en;
    return table.shareMessage.replace('{code}', code);
  }

  return {
    REPO: REPO,
    REPO_URL: REPO_URL,
    RELEASES_PAGE: RELEASES_PAGE,
    API_LATEST: API_LATEST,
    PUBLIC_URL: PUBLIC_URL,
    FALLBACK_VERSION: FALLBACK_VERSION,
    CACHE_TTL_MS: CACHE_TTL_MS,
    LANGS: LANGS,
    INVITE_PREFIX: INVITE_PREFIX,
    INVITE_ALPHABET: INVITE_ALPHABET,
    INVITE_RE: INVITE_RE,
    STRINGS: STRINGS,
    parseVersion: parseVersion,
    compareVersions: compareVersions,
    isNewer: isNewer,
    versionFromTag: versionFromTag,
    assetVersion: assetVersion,
    pickAssets: pickAssets,
    normalizeRelease: normalizeRelease,
    fallbackRelease: fallbackRelease,
    normalizeInviteCode: normalizeInviteCode,
    isValidInviteCode: isValidInviteCode,
    generateInviteCode: generateInviteCode,
    buildInviteLink: buildInviteLink,
    readInviteRef: readInviteRef,
    formatBytes: formatBytes,
    shareMessage: shareMessage
  };
});
