/*
 * Sherwood Studio installer — page behaviour.
 *
 * Everything the visitor can influence is stored in this browser only:
 * the invite code, the invite it arrived with, and how many times this device
 * shared the link. There is no account and no backend; the reward program is
 * not live yet, so nothing here pretends to track referrals server-side.
 */
(function () {
  'use strict';

  var C = window.SHWCore;
  if (!C) return;

  var KEYS = {
    code: 'shw.invite.code',
    ref: 'shw.invite.ref',
    shares: 'shw.invite.shares',
    lang: 'shw.lang',
    cache: 'shw.release.cache'
  };

  var state = {
    lang: 'en',
    code: '',
    link: '',
    ref: '',
    shares: 0,
    release: null,
    status: null
  };

  /* -------------------------------------------------------------- storage */

  function storageGet(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (error) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      window.localStorage.setItem(key, String(value));
    } catch (error) {
      /* Private mode or a locked-down WebView: run without persistence. */
    }
  }

  /* --------------------------------------------------------------- strings */

  function t(key) {
    var table = C.STRINGS[state.lang] || C.STRINGS.en;
    return table[key] || C.STRINGS.en[key] || key;
  }

  function format(key, values) {
    var text = t(key);
    if (!values) return text;
    Object.keys(values).forEach(function (name) {
      text = text.split('{' + name + '}').join(String(values[name]));
    });
    return text;
  }

  function byId(id) {
    return document.getElementById(id);
  }

  /* ------------------------------------------------------------- reference */

  function baseUrl() {
    var protocol = window.location.protocol;
    if (protocol === 'http:' || protocol === 'https:') {
      return window.location.origin + window.location.pathname;
    }
    return C.PUBLIC_URL;
  }

  function detectLang() {
    var stored = storageGet(KEYS.lang);
    if (stored && C.LANGS.indexOf(stored) !== -1) return stored;
    var list = navigator.languages && navigator.languages.length
      ? navigator.languages
      : [navigator.language || 'en'];
    for (var i = 0; i < list.length; i++) {
      var tag = String(list[i] || '').toLowerCase();
      if (tag.indexOf('sr') === 0 || tag.indexOf('hr') === 0 || tag.indexOf('bs') === 0) return 'sr';
    }
    return 'en';
  }

  function randomBytes(length) {
    var bytes = new Uint8Array(length);
    if (window.crypto && typeof window.crypto.getRandomValues === 'function') {
      window.crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < length; i++) bytes[i] = Math.floor(Math.random() * 256);
    }
    return bytes;
  }

  function loadOrCreateCode() {
    var stored = String(storageGet(KEYS.code) || '').toUpperCase();
    if (C.isValidInviteCode(stored)) return C.normalizeInviteCode(stored);
    var code = C.generateInviteCode(randomBytes(6));
    storageSet(KEYS.code, code);
    return code;
  }

  function captureIncomingInvite() {
    var incoming = C.readInviteRef(window.location.search);
    if (!C.isValidInviteCode(incoming)) return;
    if (incoming === state.code) return;
    if (storageGet(KEYS.ref)) return;
    storageSet(KEYS.ref, incoming);
  }

  /* ------------------------------------------------------------------ copy */

  function copyText(text) {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      try {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.top = '-1000px';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand('copy');
        document.body.removeChild(area);
        if (ok) resolve();
        else reject(new Error('copy rejected'));
      } catch (error) {
        reject(error);
      }
    });
  }

  function flash(button, messageKey) {
    if (!button) return;
    var label = button.hasAttribute('data-i18n')
      ? button
      : button.querySelector('[data-i18n]');
    if (!label) return;
    var original = label.getAttribute('data-i18n');
    label.textContent = t(messageKey);
    clearTimeout(label.__flashTimer);
    label.__flashTimer = setTimeout(function () {
      label.textContent = t(original);
    }, 1600);
  }

  /* -------------------------------------------------------------- releases */

  function loadCache() {
    var raw = storageGet(KEYS.cache);
    if (!raw) return null;
    try {
      var parsed = JSON.parse(raw);
      if (parsed && parsed.release && typeof parsed.at === 'number') return parsed;
    } catch (error) {
      /* A corrupt entry must never block the page. */
    }
    return null;
  }

  function refreshRelease() {
    if (navigator.onLine === false) return;
    // Old WebViews predate fetch; the cached/fallback release is already on
    // screen, so simply skip the refresh instead of throwing during init.
    if (typeof fetch !== 'function') return;
    var controller = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = controller
      ? setTimeout(function () {
        controller.abort();
      }, 8000)
      : 0;

    fetch(C.API_LATEST, {
      headers: { Accept: 'application/vnd.github+json' },
      signal: controller ? controller.signal : undefined,
      credentials: 'omit',
      cache: 'no-cache'
    })
      .then(function (response) {
        if (!response.ok) throw new Error('http ' + response.status);
        return response.json();
      })
      .then(function (raw) {
        var release = C.normalizeRelease(raw);
        if (!release.version) throw new Error('unversioned release');
        state.release = release;
        storageSet(KEYS.cache, JSON.stringify({ at: Date.now(), release: release }));
        renderRelease();
      })
      .catch(function () {
        /* Rate-limited, offline, or a proxy: keep the cached/fallback answer. */
      })
      .then(function () {
        if (timer) clearTimeout(timer);
      });
  }

  /* ---------------------------------------------------------------- render */

  function renderRelease() {
    var release = state.release;
    if (!release) return;
    var assets = release.assets || {};

    byId('versionChip').textContent = release.version
      ? format('latestVersion', { v: 'v' + release.version })
      : t('versionLoading');

    var standard = assets.standard;
    byId('downloadApk').setAttribute('href', standard ? standard.url : release.url);
    byId('downloadGplay').setAttribute('href', assets.gplay ? assets.gplay.url : release.url);
    byId('checksums').setAttribute('href', assets.sums ? assets.sums.url : release.url);
    byId('releaseNotes').setAttribute('href', release.url);
    byId('allReleases').setAttribute('href', C.RELEASES_PAGE);

    var meta = byId('fileMeta');
    var size = standard ? C.formatBytes(standard.size) : '';
    if (size) {
      meta.textContent = size + ' · .apk';
      meta.hidden = false;
    } else {
      meta.textContent = '';
      meta.hidden = true;
    }

    var notesWrap = byId('notesWrap');
    if (release.notes) {
      notesWrap.hidden = false;
      byId('notesBody').textContent = release.notes.slice(0, 1400);
    } else {
      notesWrap.hidden = true;
      byId('notesBody').textContent = '';
    }
  }

  function renderInvite() {
    byId('inviteCode').textContent = state.code;
    byId('inviteLink').value = state.link;

    var banner = byId('invitedBanner');
    if (state.ref) {
      banner.hidden = false;
      banner.textContent = format('invitedBy', { code: state.ref });
    } else {
      banner.hidden = true;
      banner.textContent = '';
    }

    byId('shareCount').textContent = format('shareCountLabel', { n: state.shares });

    var text = C.shareMessage(state.lang, state.code);
    byId('shareWhatsapp').setAttribute('href', 'https://wa.me/?text=' + encodeURIComponent(text + ' ' + state.link));
    byId('shareTelegram').setAttribute(
      'href',
      'https://t.me/share/url?url=' + encodeURIComponent(state.link) + '&text=' + encodeURIComponent(text)
    );
    byId('shareX').setAttribute(
      'href',
      'https://twitter.com/intent/tweet?text=' + encodeURIComponent(text) + '&url=' + encodeURIComponent(state.link)
    );
    byId('shareEmail').setAttribute(
      'href',
      'mailto:?subject=' + encodeURIComponent(t('shareSubject')) +
        '&body=' + encodeURIComponent(text + '\n\n' + state.link)
    );
  }

  function renderStatus() {
    var node = byId('applyStatus');
    if (!state.status) {
      node.textContent = '';
      node.className = 'status';
      return;
    }
    node.textContent = format(state.status.key, state.status.values);
    node.className = 'status ' + (state.status.ok ? 'is-ok' : 'is-err');
  }

  function setStatus(key, values, ok) {
    state.status = key ? { key: key, values: values || {}, ok: ok !== false } : null;
    renderStatus();
  }

  function renderDynamic() {
    renderRelease();
    renderInvite();
    renderStatus();
  }

  /* -------------------------------------------------------------- language */

  function applyLanguage(lang) {
    state.lang = C.LANGS.indexOf(lang) !== -1 ? lang : 'en';
    storageSet(KEYS.lang, state.lang);

    document.documentElement.lang = state.lang === 'sr' ? 'sr-Latn' : 'en';
    document.title = t('pageTitle');

    var description = document.querySelector('meta[name="description"]');
    if (description) description.setAttribute('content', t('metaDescription'));

    Array.prototype.forEach.call(document.querySelectorAll('[data-i18n]'), function (node) {
      node.textContent = t(node.getAttribute('data-i18n'));
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-i18n-placeholder]'), function (node) {
      node.setAttribute('placeholder', t(node.getAttribute('data-i18n-placeholder')));
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-lang]'), function (button) {
      var active = button.getAttribute('data-lang') === state.lang;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });

    renderDynamic();
  }

  /* ---------------------------------------------------------------- events */

  function bumpShares() {
    state.shares += 1;
    storageSet(KEYS.shares, state.shares);
    renderInvite();
  }

  function onShare() {
    var payload = {
      title: t('shareSubject'),
      text: C.shareMessage(state.lang, state.code),
      url: state.link
    };
    if (navigator.share) {
      navigator.share(payload)
        .then(bumpShares)
        .catch(function () {
          /* The user dismissed the sheet: not an error, not a share. */
        });
      return;
    }
    copyText(payload.text + '\n' + state.link)
      .then(function () {
        bumpShares();
        flash(byId('shareNative'), 'copied');
      })
      .catch(function () {
        flash(byId('shareNative'), 'copy');
      });
  }

  function bindEvents() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-lang]'), function (button) {
      button.addEventListener('click', function () {
        applyLanguage(button.getAttribute('data-lang'));
      });
    });

    byId('copyCode').addEventListener('click', function () {
      copyText(state.code)
        .then(function () {
          flash(byId('copyCode'), 'copied');
        })
        .catch(function () {
          flash(byId('copyCode'), 'copy');
        });
    });

    byId('copyLink').addEventListener('click', function () {
      copyText(state.link)
        .then(function () {
          flash(byId('copyLink'), 'copied');
        })
        .catch(function () {
          flash(byId('copyLink'), 'copy');
        });
    });

    byId('shareNative').addEventListener('click', onShare);

    ['shareWhatsapp', 'shareTelegram', 'shareX', 'shareEmail'].forEach(function (id) {
      byId(id).addEventListener('click', function () {
        bumpShares();
      });
    });

    byId('applyForm').addEventListener('submit', function (event) {
      event.preventDefault();
      var input = byId('codeInput');
      var code = C.normalizeInviteCode(input.value);
      if (!C.isValidInviteCode(code)) {
        setStatus('applyInvalid', null, false);
        return;
      }
      if (code === state.code) {
        setStatus('applySelf', null, false);
        return;
      }
      if (state.ref) {
        setStatus('applyExists', null, false);
        return;
      }
      state.ref = code;
      storageSet(KEYS.ref, code);
      input.value = '';
      setStatus('applyOk', { code: code }, true);
      renderInvite();
    });

    var input = byId('codeInput');
    input.addEventListener('input', function () {
      if (state.status && !state.status.ok) setStatus(null);
    });
  }

  /* ------------------------------------------------------------------- init */

  state.lang = detectLang();
  state.code = loadOrCreateCode();
  state.link = C.buildInviteLink(baseUrl(), state.code);
  captureIncomingInvite();
  state.ref = String(storageGet(KEYS.ref) || '').toUpperCase();
  state.shares = parseInt(storageGet(KEYS.shares) || '0', 10) || 0;

  var cache = loadCache();
  state.release = cache ? cache.release : C.fallbackRelease();

  bindEvents();
  applyLanguage(state.lang);

  if (!cache || Date.now() - cache.at > C.CACHE_TTL_MS) refreshRelease();

  if (!/Android/i.test(navigator.userAgent || '')) {
    byId('computerHint').hidden = false;
  }
})();
