/* SPEC-020/024/025 Web 遥控页逻辑：配对、设备管理、按键、文字、应用、PWA、触控板 */
(function () {
  'use strict';

  var I18N = {
    zh: {
      pairTitle: '连接电视盒子',
      pairSub: '输入电视上显示的 6 位配对码',
      codePlaceholder: '000000',
      pairBtn: '配对',
      addOther: '添加其它盒子',
      remoteTitle: '遥控器',
      power: '电源',
      volume: '音量',
      mute: '静音',
      inputTitle: '文字输入',
      textPlaceholder: '输入文字',
      send: '发送',
      appsTitle: '应用',
      devicesTitle: '设备',
      devicesEmpty: '还没有添加设备',
      scanAdd: '扫码添加',
      scanTip: '对准电视上的二维码',
      scanUnsupported: '当前页面无法调用相机（需要 HTTPS 地址），请在下方手动输入地址与配对码。',
      scanFail: '无法打开相机，请手动输入地址与配对码。',
      manualAdd: '手动添加',
      addrPlaceholder: '盒子地址，如 192.168.3.125',
      addBtn: '添加',
      delete: '删除',
      rename: '改名',
      cancel: '取消',
      invalidAddr: '地址格式不正确',
      modeDpad: '方向盘',
      modeTouch: '触摸',
      modeMouse: '鼠标',
      touchPadTip1: '点按对应位置 · 长按菜单',
      touchPadTip2: '上下滑动列表',
      mousePadTip1: '拖动移动光标 · 点按单击',
      mousePadTip2: '按住长按 · 双指滚动',
      offlineTitle: '未连接到盒子',
      offlineMsg: '请确认盒子已开机并与本机在同一局域网。也可以改用 Android 手机端 App 的蓝牙模式，或使用 2.4G 遥控器。',
      retry: '重试连接',
      foot: 'TV Remote Service · 局域网直连',
      statusConnected: '已连接',
      statusNoToken: '未配对',
      statusOffline: '未连接到盒子',
      pairOk: '配对成功',
      pairing: '正在配对…',
      pairBad: '配对码错误，请重新输入',
      pairLocked: '配对失败次数过多，请等 5 分钟后再试',
      sendOk: '已发送',
      sendFail: '发送失败',
      secureHint: '当前是 HTTP 页面：遥控功能可用，但无法安装到主屏幕或离线使用；改用 https:// 地址可获得完整能力。',
      errAuth: '配对已失效，请重新配对',
      errA11y: '盒子无障碍服务未开启，请在盒子上开启后重试',
      errCmd: '盒子不支持该按键',
      errExec: '盒子执行失败',
      errArg: '参数错误',
      errToolong: '内容过长（最多 256 个字符）',
      errLocked: '配对失败次数过多，请稍后再试',
      errProtocol: '盒子返回异常'
    },
    en: {
      pairTitle: 'Connect TV box',
      pairSub: 'Enter the 6-digit pairing code shown on the TV',
      codePlaceholder: '000000',
      pairBtn: 'Pair',
      addOther: 'Add another box',
      remoteTitle: 'Remote',
      power: 'Power',
      volume: 'Volume',
      mute: 'Mute',
      inputTitle: 'Text input',
      textPlaceholder: 'Type text',
      send: 'Send',
      appsTitle: 'Apps',
      devicesTitle: 'Devices',
      devicesEmpty: 'No devices added yet',
      scanAdd: 'Scan to add',
      scanTip: 'Point at the QR code on the TV',
      scanUnsupported: 'This page cannot use the camera (HTTPS address required). Enter the address and pairing code below.',
      scanFail: 'Could not open the camera. Enter the address and code manually.',
      manualAdd: 'Add manually',
      addrPlaceholder: 'Box address, e.g. 192.168.3.125',
      addBtn: 'Add',
      delete: 'Delete',
      rename: 'Rename',
      cancel: 'Cancel',
      invalidAddr: 'Invalid address',
      modeDpad: 'D-pad',
      modeTouch: 'Touch',
      modeMouse: 'Mouse',
      touchPadTip1: 'Tap where you point · long-press for menu',
      touchPadTip2: 'Swipe up or down to scroll',
      mousePadTip1: 'Drag to move cursor · tap to click',
      mousePadTip2: 'Hold to long-press · two-finger scroll',
      offlineTitle: 'Not connected to the box',
      offlineMsg: 'Make sure the box is on and on the same network. You can also use the Bluetooth mode in the Android app, or a 2.4G remote.',
      retry: 'Retry',
      foot: 'TV Remote Service · LAN direct',
      statusConnected: 'Connected',
      statusNoToken: 'Not paired',
      statusOffline: 'Not connected',
      pairOk: 'Paired',
      pairing: 'Pairing…',
      pairBad: 'Wrong pairing code, try again',
      pairLocked: 'Too many failed attempts, wait 5 minutes',
      sendOk: 'Sent',
      sendFail: 'Send failed',
      secureHint: 'This is an HTTP page: the remote works, but install and offline mode require an https:// address.',
      errAuth: 'Pairing expired, please pair again',
      errA11y: 'Accessibility service is off on the box',
      errCmd: 'The box does not support this key',
      errExec: 'The box failed to run the command',
      errArg: 'Invalid argument',
      errToolong: 'Text too long (up to 256 characters)',
      errLocked: 'Too many failed attempts, try later',
      errProtocol: 'Unexpected response from the box'
    }
  };

  var lang = ((navigator.languages && navigator.languages[0]) ||
              navigator.language || 'zh').toLowerCase().indexOf('zh') === 0 ? 'zh' : 'en';
  var T = I18N[lang];

  var DEVICES_KEY = 'tvremoteweb_devices';
  var CURRENT_KEY = 'tvremoteweb_current';
  var INPUT_KEY = 'tvremoteweb_input';
  /* 旧版单设备 token 键，仅用于首次升级迁移 */
  var LEGACY_TOKEN_KEY = 'tvremoteweb_token';

  var DRAG_THRESHOLD = 8;
  var LONG_PRESS_MS = 500;
  /* 鼠标模式：相对位移增益、20ms 合并、滚动一格 24px、单次最多 5 格、最小间隔 100ms */
  var MOVE_GAIN = 1.5;
  var MOVE_FLUSH_MS = 20;
  var SCROLL_UNIT = 24;
  var SCROLL_STEP_MAX = 5;
  var SCROLL_INTERVAL = 100;

  var $ = function (s) { return document.querySelector(s); };
  var toastTimer = null;

  var devices = [];
  var current = '';

  function t(key) { return T[key] || key; }

  function lsGet(key) {
    try { return localStorage.getItem(key) || ''; } catch (e) { return ''; }
  }

  function lsSet(key, value) {
    try { localStorage.setItem(key, value); } catch (e) {}
  }

  function lsDel(key) {
    try { localStorage.removeItem(key); } catch (e) {}
  }

  function applyI18n() {
    document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].textContent = t(nodes[i].getAttribute('data-i18n'));
    }
    var phs = document.querySelectorAll('[data-i18n-ph]');
    for (var j = 0; j < phs.length; j++) {
      phs[j].setAttribute('placeholder', t(phs[j].getAttribute('data-i18n-ph')));
    }
  }

  function toast(msg) {
    var el = $('#toast');
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 2400);
  }

  function setStatus(text, cls) {
    var el = $('#status');
    el.textContent = text;
    el.className = 'status' + (cls ? ' ' + cls : '');
  }

  function setOnline(on) {
    if (on) {
      $('#offlineCard').hidden = true;
      setStatus(t('statusConnected'), 'ok');
    } else {
      $('#offlineCard').hidden = false;
      setStatus(t('statusOffline'), 'bad');
    }
  }

  function errText(code) {
    switch (code) {
      case 'auth': return t('errAuth');
      case 'a11y': return t('errA11y');
      case 'cmd': return t('errCmd');
      case 'exec': return t('errExec');
      case 'arg': return t('errArg');
      case 'toolong': return t('errToolong');
      case 'locked': return t('errLocked');
      default: return t('errProtocol');
    }
  }

  function pairErrText(code) {
    if (code === 'locked') return t('pairLocked');
    if (code === 'offline') return t('statusOffline');
    if (code === 'badcode') return t('pairBad');
    return errText(code);
  }

  // ---------------- 设备列表（SPEC-025） ----------------

  function hostName(base) {
    try { return new URL(base).hostname; } catch (e) { return base; }
  }

  function findDevice(base) {
    if (!base) return null;
    for (var i = 0; i < devices.length; i++) {
      if (devices[i].base === base) return devices[i];
    }
    return null;
  }

  function tokenOf(base) {
    var d = findDevice(base === undefined ? current : base);
    return d ? (d.token || '') : '';
  }

  function saveDevices() {
    lsSet(DEVICES_KEY, JSON.stringify(devices));
    lsSet(CURRENT_KEY, current);
  }

  function loadDevices() {
    try {
      var raw = JSON.parse(lsGet(DEVICES_KEY) || '[]');
      devices = raw && raw.length ? raw : [];
    } catch (e) {
      devices = [];
    }
    devices = devices.filter(function (d) {
      return d && typeof d.base === 'string' && d.base;
    });
    current = lsGet(CURRENT_KEY);
    if (!findDevice(current)) current = '';

    // 旧版只存了当前源的一个 token：迁移成设备条目
    var legacy = lsGet(LEGACY_TOKEN_KEY);
    if (legacy && !devices.length) {
      devices.push({ base: location.origin, name: hostName(location.origin), token: legacy });
      current = location.origin;
      saveDevices();
      lsDel(LEGACY_TOKEN_KEY);
    }
    if (!current && devices.length) current = devices[0].base;
  }

  /** 规范化设备地址：允许省略协议与端口 */
  function normBase(input) {
    var s = (input || '').trim();
    if (!s) return '';
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
    var u;
    try {
      u = new URL(s);
    } catch (e) {
      return '';
    }
    if (!u.hostname) return '';
    if (!u.port) u.port = u.protocol === 'https:' ? '17843' : '17880';
    return u.protocol + '//' + u.hostname + ':' + u.port;
  }

  function updateDevBar() {
    $('#devBtn').hidden = devices.length === 0;
    var d = findDevice(current);
    $('#devName').textContent = d ? (d.name || hostName(d.base)) : '';
  }

  function renderDevices() {
    var box = $('#devList');
    box.innerHTML = '';
    if (!devices.length) {
      var empty = document.createElement('p');
      empty.className = 'sub';
      empty.textContent = t('devicesEmpty');
      box.appendChild(empty);
    }
    devices.forEach(function (d) {
      var row = document.createElement('div');
      row.className = 'devRow' + (d.base === current ? ' on' : '');

      var pick = document.createElement('button');
      pick.className = 'devPick';
      var name = document.createElement('span');
      name.className = 'devPickName';
      name.textContent = d.name || hostName(d.base);
      var addr = document.createElement('span');
      addr.className = 'devPickAddr';
      addr.textContent = d.base;
      pick.appendChild(name);
      pick.appendChild(addr);
      pick.addEventListener('click', function () { switchDevice(d.base); });

      var edit = document.createElement('button');
      edit.className = 'devEdit';
      edit.textContent = t('rename');
      edit.addEventListener('click', function (ev) {
        ev.stopPropagation();
        startRename(d, name);
      });

      var del = document.createElement('button');
      del.className = 'devDel';
      del.textContent = t('delete');
      del.addEventListener('click', function (ev) {
        ev.stopPropagation();
        removeDevice(d.base);
      });

      row.appendChild(pick);
      row.appendChild(edit);
      row.appendChild(del);
      box.appendChild(row);
    });
    updateDevBar();
  }

  /** 设备名称就地编辑：回车/失焦保存，Esc 取消；空名称回落为地址中的 IP */
  function startRename(d, nameEl) {
    var input = document.createElement('input');
    input.className = 'devRename';
    input.value = d.name || '';
    input.maxLength = 32;
    input.autocomplete = 'off';
    nameEl.replaceWith(input);
    input.focus();
    input.select();

    var done = false;
    function commit(save) {
      if (done) return;
      done = true;
      if (save) {
        d.name = input.value.trim();
        saveDevices();
      }
      renderDevices();
    }
    input.addEventListener('keydown', function (ev) {
      ev.stopPropagation();
      if (ev.key === 'Enter') commit(true);
      else if (ev.key === 'Escape') commit(false);
    });
    input.addEventListener('click', function (ev) { ev.stopPropagation(); });
    input.addEventListener('blur', function () { commit(true); });
  }

  function openSheet() {
    renderDevices();
    $('#devMask').hidden = false;
    $('#devSheet').hidden = false;
    $('#devMsg').textContent = '';
  }

  function closeSheet() {
    closeScan();
    $('#devMask').hidden = true;
    $('#devSheet').hidden = true;
  }

  function switchDevice(base) {
    if (!findDevice(base)) return;
    resetPad();
    if (base === current) {
      closeSheet();
      return;
    }
    current = base;
    saveDevices();
    closeSheet();
    var d = findDevice(base);
    if (!d || !d.token) {
      showPair();
      updateDevBar();
      return;
    }
    showMain();
    updateDevBar();
    ping();
    // 切设备后按当前模式重置光标（鼠标模式重新显示，其余模式隐藏）
    setPadMode(inputMode(), false);
  }

  function removeDevice(base) {
    devices = devices.filter(function (d) { return d.base !== base; });
    if (current === base) current = '';
    if (!current && devices.length) current = devices[0].base;
    saveDevices();
    renderDevices();
    if (!current) {
      showPair();
      return;
    }
    var d = findDevice(current);
    if (d && d.token) {
      showMain();
      ping();
    } else {
      showPair();
    }
  }

  // ---------------- HTTP ----------------

  function request(path, method, body, opts) {
    opts = opts || {};
    var base = opts.base || current || location.origin;
    var useToken = opts.useToken !== false;
    var tok = useToken ? tokenOf(base) : '';

    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, 8000);
    var headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (useToken && tok) headers['X-Token'] = tok;
    var o = { method: method || 'GET', headers: headers, cache: 'no-store' };
    if (body !== undefined) o.body = JSON.stringify(body);
    if (ctrl) o.signal = ctrl.signal;
    return fetch(base + path, o).then(function (res) {
      clearTimeout(timer);
      if (res.status === 401) {
        authFailed(base);
        var e = new Error('auth');
        e.auth = true;
        throw e;
      }
      return res;
    }, function (e) {
      clearTimeout(timer);
      throw e;
    });
  }

  function api(path, method, body, opts) {
    return request(path, method, body, opts).then(function (res) {
      var ct = res.headers.get('content-type') || '';
      if (res.status !== 200) throw new Error('HTTP ' + res.status);
      if (ct.indexOf('json') >= 0) return res.json();
      return res.blob();
    });
  }

  function authFailed(base) {
    var d = findDevice(base);
    if (d) {
      d.token = '';
      saveDevices();
    }
    resetPad();
    showPair();
    updateDevBar();
  }

  function command(path, body) {
    return api(path, 'POST', body).then(function (r) {
      if (r && r.ok === false) {
        if (!$('#pairCard').hidden) $('#pairMsg').textContent = errText(r.err);
        else toast(errText(r.err));
        return false;
      }
      setOnline(true);
      return true;
    }).catch(function (e) {
      if (!e || !e.auth) setOnline(false);
      return false;
    });
  }

  function ping() {
    if (!current) {
      showPair();
      return;
    }
    api('/api/ping', 'GET', undefined, { useToken: false }).then(function (r) {
      if (r && r.ok) {
        setOnline(true);
        if (tokenOf(current)) loadApps();
        return;
      }
      setOnline(false);
    }).catch(function () {
      setOnline(false);
    });
  }

  // ---------------- 配对与主界面 ----------------

  function showPair() {
    $('#pairCard').hidden = false;
    $('#remoteCard').hidden = true;
    $('#inputCard').hidden = true;
    $('#appsCard').hidden = true;
    $('#offlineCard').hidden = true;
    setStatus(t('statusNoToken'));
    updateDevBar();
  }

  function showMain() {
    $('#pairCard').hidden = true;
    $('#remoteCard').hidden = false;
    $('#inputCard').hidden = false;
    $('#appsCard').hidden = false;
    setStatus(t('statusConnected'), 'ok');
    updateDevBar();
  }

  function doPair() {
    var code = ($('#code').value || '').trim();
    if (!/^\d{6}$/.test(code)) {
      $('#pairMsg').textContent = t('pairBad');
      return;
    }
    $('#pairMsg').textContent = '';
    var base = current || location.origin;
    pairWith(code, base).then(function (res) {
      if (!res.ok) $('#pairMsg').textContent = pairErrText(res.err);
    });
  }

  /** 对指定设备地址配对；成功后写入设备列表并切为当前设备 */
  function pairWith(code, base) {
    base = base || current || location.origin;
    return api('/api/pair', 'POST', { code: code }, { base: base, useToken: false })
      .then(function (r) {
        if (r && r.ok && r.token) {
          var d = findDevice(base);
          if (!d) {
            d = { base: base, name: hostName(base), token: r.token };
            devices.push(d);
          }
          d.token = r.token;
          current = base;
          saveDevices();
          try { history.replaceState(null, '', location.pathname + location.search); } catch (e) {}
          showMain();
          renderDevices();
          setOnline(true);
          loadApps();
          return { ok: true };
        }
        if (r && r.err === 'locked') return { ok: false, err: 'locked' };
        if (r && r.err) return { ok: false, err: r.err };
        return { ok: false, err: 'badcode' };
      })
      .catch(function () {
        return { ok: false, err: 'offline' };
      });
  }

  /** SPEC-024：二维码地址形如 #pair=123456，扫码打开后自动配对 */
  function hashPairCode() {
    var m = /pair=(\d{6})/.exec(location.hash || '');
    return m ? m[1] : '';
  }

  function addManual() {
    var base = normBase($('#devAddr').value);
    var code = ($('#devCode').value || '').trim();
    if (!base) {
      $('#devMsg').textContent = t('invalidAddr');
      return;
    }
    if (!/^\d{6}$/.test(code)) {
      $('#devMsg').textContent = t('pairBad');
      return;
    }
    $('#devMsg').textContent = t('pairing');
    pairWith(code, base).then(function (res) {
      if (res.ok) {
        $('#devAddr').value = '';
        $('#devCode').value = '';
        closeSheet();
        toast(t('pairOk'));
      } else {
        $('#devMsg').textContent = pairErrText(res.err);
      }
    });
  }

  // ---------------- 扫码添加 ----------------

  var scanStream = null;
  var scanTimer = null;

  function scannerSupported() {
    return !!(window.isSecureContext && window.BarcodeDetector &&
              navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  }

  function setupScannerUI() {
    var ok = scannerSupported();
    $('#scanBtn').hidden = !ok;
    if (!ok) {
      $('#scanHint').hidden = false;
      $('#scanHint').textContent = t('scanUnsupported');
    }
  }

  function openScan() {
    if (!scannerSupported()) return;
    navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'environment' } }
    }).then(function (stream) {
      scanStream = stream;
      var v = $('#scanVideo');
      v.srcObject = stream;
      $('#scanOverlay').hidden = false;
      if (v.play) v.play();
      var detector = new BarcodeDetector({ formats: ['qr_code'] });
      scanTimer = setInterval(function () {
        detector.detect(v).then(function (list) {
          if (!list || !list.length) return;
          var m = /^(https?:\/\/[^#\s]+)#pair=(\d{6})/.exec(list[0].rawValue || '');
          if (!m) return;
          closeScan();
          addScanned(m[1], m[2]);
        }).catch(function () {});
      }, 300);
    }).catch(function () {
      $('#devMsg').textContent = t('scanFail');
    });
  }

  function closeScan() {
    if (scanTimer) { clearInterval(scanTimer); scanTimer = null; }
    if (scanStream) {
      scanStream.getTracks().forEach(function (tr) { tr.stop(); });
      scanStream = null;
    }
    var v = $('#scanVideo');
    if (v) v.srcObject = null;
    $('#scanOverlay').hidden = true;
  }

  function addScanned(url, code) {
    var base = normBase(url);
    if (!base) {
      $('#devMsg').textContent = t('invalidAddr');
      return;
    }
    $('#devMsg').textContent = t('pairing');
    pairWith(code, base).then(function (res) {
      if (res.ok) {
        closeSheet();
        toast(t('pairOk'));
      } else {
        $('#devMsg').textContent = pairErrText(res.err);
      }
    });
  }

  // ---------------- 应用 ----------------

  function loadApps() {
    if (!current || !tokenOf(current)) return;
    api('/api/apps', 'GET').then(function (r) {
      if (!r || !r.ok || !r.apps) return;
      var box = $('#apps');
      box.innerHTML = '';
      r.apps.forEach(function (app) {
        var btn = document.createElement('button');
        btn.className = 'appItem';
        var ph = document.createElement('span');
        ph.className = 'ph';
        btn.appendChild(ph);
        var label = document.createElement('span');
        label.textContent = app.name || app.pkg;
        btn.appendChild(label);
        btn.addEventListener('click', function () { command('/api/app', { pkg: app.pkg }); });
        box.appendChild(btn);
        // 图标加载失败时保留占位块，避免出现破图
        loadIcon(app.pkg).then(function (url) {
          if (!url) return;
          var img = document.createElement('img');
          img.alt = '';
          img.src = url;
          btn.insertBefore(img, ph);
          ph.remove();
        });
      });
      setOnline(true);
    }).catch(function (e) {
      if (!e || !e.auth) setOnline(false);
    });
  }

  function loadIcon(pkg) {
    return api('/api/icon?pkg=' + encodeURIComponent(pkg), 'GET').then(function (r) {
      if (r && typeof r === 'object' && r.size > 0) return URL.createObjectURL(r);
      return '';
    }).catch(function () { return ''; });
  }

  // ---------------- 按键 ----------------

  function sendKey(code) { return command('/api/key', { code: code }); }

  function bindKeys() {
    var btns = document.querySelectorAll('[data-key]');
    for (var i = 0; i < btns.length; i++) {
      (function (btn) {
        btn.addEventListener('click', function () {
          sendKey(parseInt(btn.getAttribute('data-key'), 10));
        });
      })(btns[i]);
    }
  }

  function bindOk() {
    var ok = $('#okBtn');
    var longTimer = null;
    var longFired = false;

    ok.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      longFired = false;
      longTimer = setTimeout(function () {
        longFired = true;
        command('/api/keylong', { code: 23 });
      }, 550);
    });

    ok.addEventListener('pointerup', function (e) {
      e.preventDefault();
      if (longTimer) { clearTimeout(longTimer); longTimer = null; }
      if (!longFired) sendKey(23);
      longFired = false;
    });

    ok.addEventListener('pointercancel', function () {
      if (longTimer) { clearTimeout(longTimer); longTimer = null; }
      longFired = false;
    });
  }

  // ---------------- 触控板（SPEC-028 触摸 / SPEC-029 鼠标） ----------------

  var padEl = null;
  /* 'touch'：绝对坐标点按；'mouse'：相对移动光标 */
  var padMode = 'touch';
  /* 触摸模式状态：单指绝对坐标 */
  var pad = {
    active: false,
    pointerId: null,
    downX: 0,
    downY: 0,
    downNx: 0,
    downNy: 0,
    lastX: 0,
    lastY: 0,
    downAt: 0,
    moved: false,
    longFired: false,
    longTimer: null
  };
  /* 鼠标模式状态：沿用 SPEC-025 的相对位移、左键、长按与双指滚动 */
  var mouse = {
    pointers: {},
    lastX: 0,
    lastY: 0,
    moved: 0,
    longTimer: null,
    longPending: false,
    pressed: false,
    scrolling: false,
    scrollStartY: 0,
    scrollAcc: 0,
    lastScrollAt: 0
  };
  var pendX = 0;
  var pendY = 0;
  var moveTimer = null;

  /* 触控命令串行发送，保证「按下 -> 移动 -> 抬起」的顺序 */
  var cmdQueue = [];
  var cmdBusy = false;

  function enqueueCmd(path, body) {
    if (!tokenOf(current)) return;
    cmdQueue.push({ path: path, body: body || {} });
    pumpQueue();
  }

  function pumpQueue() {
    if (cmdBusy) return;
    var c = cmdQueue.shift();
    if (!c) return;
    cmdBusy = true;
    api(c.path, 'POST', c.body).then(function (r) {
      cmdBusy = false;
      if (r && r.ok === false) {
        if (r.err === 'a11y') toast(errText('a11y'));
      } else {
        setOnline(true);
      }
      pumpQueue();
    }, function (e) {
      cmdBusy = false;
      cmdQueue.length = 0;
      resetPad();
      if (!e || !e.auth) setOnline(false);
    });
  }

  /** 触控板内任意位置换算成盒子屏幕的归一化坐标（0..10000） */
  function padPoint(clientX, clientY) {
    var r = padEl.getBoundingClientRect();
    var x = clamp(Math.round((clientX - r.left) * 10000 / Math.max(1, r.width)), 0, 10000);
    var y = clamp(Math.round((clientY - r.top) * 10000 / Math.max(1, r.height)), 0, 10000);
    return { x: x, y: y };
  }

  function resetPad() {
    if (pad.longTimer) { clearTimeout(pad.longTimer); pad.longTimer = null; }
    pad.active = false;
    pad.pointerId = null;
    pad.moved = false;
    pad.longFired = false;
    // 鼠标模式只清状态，不在这里发命令：错误回调也会调用 resetPad，避免递归
    if (mouse.longTimer) { clearTimeout(mouse.longTimer); mouse.longTimer = null; }
    if (moveTimer) { clearTimeout(moveTimer); moveTimer = null; }
    pendX = 0;
    pendY = 0;
    mouse.pressed = false;
    mouse.pointers = {};
    mouse.scrolling = false;
    mouse.longPending = false;
    mouse.moved = 0;
  }

  /** 鼠标模式主动释放左键（切模式、页面隐藏时调用） */
  function releaseMouse() {
    flushMove();
    if (mouse.pressed) {
      mouse.pressed = false;
      enqueueCmd('/api/click', { down: false });
    }
  }

  // ---- 触摸模式：绝对坐标 ----

  function touchDown(e) {
    if (pad.active) {
      // 第二根手指落下：取消本次手势，不发命令
      resetPad();
      return;
    }
    try { padEl.setPointerCapture(e.pointerId); } catch (err) {}
    var p = padPoint(e.clientX, e.clientY);
    pad.active = true;
    pad.pointerId = e.pointerId;
    pad.downX = pad.lastX = e.clientX;
    pad.downY = pad.lastY = e.clientY;
    pad.downNx = p.x;
    pad.downNy = p.y;
    pad.downAt = Date.now();
    pad.moved = false;
    pad.longFired = false;
    pad.longTimer = setTimeout(function () {
      pad.longTimer = null;
      if (!pad.active || pad.moved) return;
      pad.longFired = true;
      enqueueCmd('/api/longn', { x: pad.downNx, y: pad.downNy });
    }, LONG_PRESS_MS);
  }

  function touchMove(e) {
    if (!pad.active || e.pointerId !== pad.pointerId) return;
    pad.lastX = e.clientX;
    pad.lastY = e.clientY;
    if (!pad.moved
        && Math.hypot(pad.lastX - pad.downX, pad.lastY - pad.downY) > DRAG_THRESHOLD) {
      pad.moved = true;
      if (pad.longTimer) { clearTimeout(pad.longTimer); pad.longTimer = null; }
    }
  }

  function touchUp(e) {
    if (!pad.active || e.pointerId !== pad.pointerId) return;
    try { padEl.releasePointerCapture(e.pointerId); } catch (err) {}
    if (pad.longTimer) { clearTimeout(pad.longTimer); pad.longTimer = null; }
    if (!pad.longFired) {
      if (pad.moved) {
        var a = padPoint(pad.downX, pad.downY);
        var b = padPoint(pad.lastX, pad.lastY);
        var ms = clamp(Date.now() - pad.downAt, 50, 1500);
        enqueueCmd('/api/swipen', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, ms: ms });
      } else {
        enqueueCmd('/api/tapn', { x: pad.downNx, y: pad.downNy });
      }
    }
    resetPad();
  }

  function touchCancel(e) {
    if (e.pointerId !== pad.pointerId) return;
    resetPad();
  }

  // ---- 鼠标模式：相对位移 ----

  function queueMove(dx, dy) {
    pendX += dx;
    pendY += dy;
    if (moveTimer) return;
    moveTimer = setTimeout(function () {
      moveTimer = null;
      flushMove();
    }, MOVE_FLUSH_MS);
  }

  function flushMove() {
    if (moveTimer) {
      clearTimeout(moveTimer);
      moveTimer = null;
    }
    var dx = Math.round(pendX);
    var dy = Math.round(pendY);
    pendX -= dx;
    pendY -= dy;
    while (dx !== 0 || dy !== 0) {
      var sx = clamp(dx, -127, 127);
      var sy = clamp(dy, -127, 127);
      enqueueCmd('/api/move', { dx: sx, dy: sy });
      dx -= sx;
      dy -= sy;
    }
  }

  function pointerCount() {
    var n = 0;
    for (var id in mouse.pointers) {
      if (Object.prototype.hasOwnProperty.call(mouse.pointers, id)) n++;
    }
    return n;
  }

  function avgY() {
    var sum = 0;
    var n = 0;
    for (var id in mouse.pointers) {
      if (Object.prototype.hasOwnProperty.call(mouse.pointers, id)) {
        sum += mouse.pointers[id].y;
        n++;
      }
    }
    return n ? sum / n : 0;
  }

  function mouseDown(e) {
    try { padEl.setPointerCapture(e.pointerId); } catch (err) {}
    mouse.pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    var n = pointerCount();

    if (n === 1) {
      mouse.lastX = e.clientX;
      mouse.lastY = e.clientY;
      mouse.moved = 0;
      mouse.longPending = false;
      mouse.pressed = false;
      mouse.scrolling = false;
      if (mouse.longTimer) clearTimeout(mouse.longTimer);
      mouse.longTimer = setTimeout(function () {
        mouse.longTimer = null;
        mouse.longPending = true;
      }, LONG_PRESS_MS);
      flushMove();
    } else if (n === 2) {
      if (mouse.longTimer) { clearTimeout(mouse.longTimer); mouse.longTimer = null; }
      if (mouse.pressed) {
        mouse.pressed = false;
        flushMove();
        enqueueCmd('/api/click', { down: false });
      }
      mouse.longPending = false;
      mouse.scrolling = true;
      mouse.scrollStartY = avgY();
      mouse.scrollAcc = 0;
      mouse.lastScrollAt = 0;
    }
  }

  function mouseMove(e) {
    var p = mouse.pointers[e.pointerId];
    if (!p) return;
    p.x = e.clientX;
    p.y = e.clientY;

    if (mouse.scrolling) {
      handleScroll();
      return;
    }
    if (pointerCount() !== 1) return;

    var dx = e.clientX - mouse.lastX;
    var dy = e.clientY - mouse.lastY;
    mouse.lastX = e.clientX;
    mouse.lastY = e.clientY;
    mouse.moved += Math.abs(dx) + Math.abs(dy);
    if (mouse.moved <= DRAG_THRESHOLD) return;

    if (mouse.longTimer) { clearTimeout(mouse.longTimer); mouse.longTimer = null; }
    if (!mouse.pressed) {
      flushMove();
      mouse.pressed = true;
      enqueueCmd('/api/click', { down: true });
    }
    queueMove(Math.round(dx * MOVE_GAIN), Math.round(dy * MOVE_GAIN));
  }

  function mouseUp(e) {
    if (!mouse.pointers[e.pointerId]) return;
    try { padEl.releasePointerCapture(e.pointerId); } catch (err) {}
    delete mouse.pointers[e.pointerId];

    if (mouse.scrolling) {
      if (pointerCount() < 2) mouse.scrolling = false;
      mouse.scrollAcc = 0;
      return;
    }
    if (pointerCount() > 0) return;

    if (mouse.longTimer) { clearTimeout(mouse.longTimer); mouse.longTimer = null; }
    flushMove();
    if (mouse.pressed) {
      mouse.pressed = false;
      enqueueCmd('/api/click', { down: false });
    } else if (mouse.longPending) {
      enqueueCmd('/api/long', {});
    } else if (mouse.moved <= DRAG_THRESHOLD) {
      enqueueCmd('/api/tap', {});
    }
    mouse.longPending = false;
    mouse.moved = 0;
  }

  function handleScroll() {
    var y = avgY();
    mouse.scrollAcc += y - mouse.scrollStartY;
    mouse.scrollStartY = y;
    var steps = Math.trunc(mouse.scrollAcc / SCROLL_UNIT);
    if (steps === 0) return;
    var now = Date.now();
    if (now - mouse.lastScrollAt < SCROLL_INTERVAL) return;
    mouse.scrollAcc -= steps * SCROLL_UNIT;
    mouse.lastScrollAt = now;
    var s = clamp(steps, -SCROLL_STEP_MAX, SCROLL_STEP_MAX);
    if (s === 0) return;
    enqueueCmd('/api/scroll', { steps: s });
  }

  // ---- 事件分发 ----

  function onPadDown(e) {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    e.preventDefault();
    if (padMode === 'mouse') mouseDown(e); else touchDown(e);
  }

  function onPadMove(e) {
    e.preventDefault();
    if (padMode === 'mouse') mouseMove(e); else touchMove(e);
  }

  function onPadUp(e) {
    e.preventDefault();
    if (padMode === 'mouse') mouseUp(e); else touchUp(e);
  }

  function onPadCancel(e) {
    if (padMode === 'mouse') {
      delete mouse.pointers[e.pointerId];
      releaseMouse();
      resetPad();
    } else {
      touchCancel(e);
    }
  }

  function bindPad() {
    padEl = $('#touchpad');
    padEl.addEventListener('pointerdown', onPadDown);
    padEl.addEventListener('pointermove', onPadMove);
    padEl.addEventListener('pointerup', onPadUp);
    padEl.addEventListener('pointercancel', onPadCancel);
  }

  /** 读取输入模式并迁移旧值 touchpad -> touch */
  function inputMode() {
    var v = lsGet(INPUT_KEY);
    if (v === 'touchpad') {
      v = 'touch';
      lsSet(INPUT_KEY, v);
    }
    return (v === 'touch' || v === 'mouse') ? v : 'dpad';
  }

  /** 按当前模式设置触控板提示文案 */
  function updatePadTips() {
    $('#padTip1').textContent = t(padMode === 'mouse' ? 'mousePadTip1' : 'touchPadTip1');
    $('#padTip2').textContent = t(padMode === 'mouse' ? 'mousePadTip2' : 'touchPadTip2');
  }

  /** 切换输入模式：'dpad' / 'touch' / 'mouse'；persist=true 时写入本地 */
  function setPadMode(mode, persist) {
    releaseMouse();
    resetPad();
    padMode = mode === 'mouse' ? 'mouse' : 'touch';
    var isDpad = mode === 'dpad';
    $('#dpad').hidden = !isDpad;
    $('#touchpad').hidden = isDpad;
    $('#segDpad').classList.toggle('on', isDpad);
    $('#segPad').classList.toggle('on', mode === 'touch');
    $('#segMouse').classList.toggle('on', mode === 'mouse');
    if (persist) lsSet(INPUT_KEY, isDpad ? 'dpad' : padMode);
    updatePadTips();
    // 先清理可能残留的光标；鼠标模式随后让盒端按上次位置重建光标
    enqueueCmd('/api/hide');
    if (mode === 'mouse') enqueueCmd('/api/move', { dx: 0, dy: 0 });
  }

  function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

  // ---------------- 初始化 ----------------

  function bind() {
    $('#devBtn').addEventListener('click', openSheet);
    $('#devMask').addEventListener('click', closeSheet);
    $('#devClose').addEventListener('click', closeSheet);
    $('#scanBtn').addEventListener('click', openScan);
    $('#scanCancel').addEventListener('click', closeScan);
    $('#addBtn').addEventListener('click', addManual);
    $('#devCode').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') addManual();
    });
    $('#pairBtn').addEventListener('click', doPair);
    $('#addOtherBtn').addEventListener('click', openSheet);
    $('#code').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') doPair();
    });
    $('#segDpad').addEventListener('click', function () { setPadMode('dpad', true); });
    $('#segPad').addEventListener('click', function () { setPadMode('touch', true); });
    $('#segMouse').addEventListener('click', function () { setPadMode('mouse', true); });
    $('#sendBtn').addEventListener('click', function () {
      var el = $('#text');
      var v = el.value;
      if (!v) return;
      command('/api/text', { text: v }).then(function (ok) {
        if (ok) { el.value = ''; toast(t('sendOk')); }
      });
    });
    $('#text').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') $('#sendBtn').click();
    });
    $('#retryBtn').addEventListener('click', ping);
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) {
        releaseMouse();
        resetPad();
        enqueueCmd('/api/hide');
      }
    });
    window.addEventListener('offline', function () { setOnline(false); });
    bindKeys();
    bindOk();
    bindPad();
  }

  function boot() {
    loadDevices();
    applyI18n();
    setupScannerUI();
    bind();
    setPadMode(inputMode(), false);
    renderDevices();

    if (!window.isSecureContext) {
      $('#secureHint').hidden = false;
      $('#secureHint').textContent = t('secureHint');
    }

    var hashCode = hashPairCode();
    if (hashCode) {
      // SPEC-024 扫码进入：直接带码配对，配对卡只在失败时用于手动重输
      $('#code').value = hashCode;
      showPair();
      $('#pairMsg').textContent = t('pairing');
      pairWith(hashCode, location.origin).then(function (res) {
        if (!res.ok) $('#pairMsg').textContent = pairErrText(res.err);
      });
    } else if (current && tokenOf(current)) {
      showMain();
      ping();
    } else {
      showPair();
    }

    if ('serviceWorker' in navigator && window.isSecureContext) {
      navigator.serviceWorker.register('/sw.js').catch(function () {});
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
