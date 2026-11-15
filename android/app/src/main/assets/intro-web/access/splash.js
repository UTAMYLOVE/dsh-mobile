/* ============================================================================
 * dsh-startup-screen — 启动动画运行时（浏览器端，零依赖）
 * Copyright (c) 2026 沈宏宏 — MIT License (see LICENSE in this folder).
 * Source: https://github.com/6shenhonghong9/dsh-startup-screen
 *         commit 7f3d2837c1aaef8293abc69093b0a35c80d0f0e8, file lib/splash.js
 * ----------------------------------------------------------------------------
 *   第一幕  黑幕 + 标识／中文名／英文名（字距收紧 + 扫光）
 *   过场    过曝洗白（黑 → 灰 → 暖白底板）
 *   第二幕  暖白"技术档案"底板：左上品牌块、右下页脚、进度条
 *   序列    需要访问许可 → 身份资料确认 → 接收访问需求 → 开始读取权限 → 读取完毕·核验通过
 *   终幕    WELCOME TO / [品牌框] / 标识，青红色散故障后揭幕
 *
 * DSH Mobile adaptation (every change is listed in NOTICE.md):
 *  - parameters come from the URL query string, not from a DSH host;
 *  - silent: no voice files, no sound effects, no speech synthesis; each line's
 *    original voice length is kept as a silent beat so the timing stays the same;
 *  - the DeepSeek whale and wordmarks are gone: the user's picture (picture.img)
 *    or a plain drawn figure stands where the whale was; brand text is DSH MOBILE;
 *  - on-screen Chinese is Traditional; names/greeting come from the app;
 *  - no storage, no network, no desktop title-bar hack, no replay/re-inject,
 *    no app-backdrop blur; finishing calls window.DSHM.done() and sets #done.
 * ========================================================================== */
(function () {
  'use strict'

  var ROOT_ID = 'dsh-startup-root'

  /* DSHM: a plain figure (head + shoulders) drawn here, used when there is no
     picture. It replaces the DeepSeek whale path, which is not shipped. */
  var FIGURE_PATH =
    'M33 17.5A8 8 0 1 1 17 17.5A8 8 0 1 1 33 17.5Z' +
    'M9 44C9 34.5 16.2 28.5 25 28.5C33.8 28.5 41 34.5 41 44Z'

  /* ── 序列脚本：中文主字 + 英文等宽字幕 + 底部日志 ───────────────────────
     DSHM: `voiceMs` is the length of the original recorded line for that step
     (AUDIO-FIELDS.md: 1.96 / 1.16 / 1.07 / 1.18 / 1.70 s). The recordings are
     not shipped; the length is kept as a silent beat so each step lasts as long
     as it did with the voice. */
  var STEPS = [
    {
      zh: '需要訪問許可',
      en: 'ACCESS PERMISSION REQUIRED',
      voiceMs: 1960,
      log: '等待用戶授權…',
      logEn: 'AWAITING OPERATOR CONSENT',
      badge: 'AUTHORIZATION',
      action: 'grant',
      btnZh: '授予訪問權限',
      btnEn: 'GRANT ACCESS',
      hold: 900,
      progress: 0.34
    },
    {
      zh: '身份資料確認',
      en: 'IDENTITY VERIFICATION',
      voiceMs: 1160,
      log: '核對身份資料…',
      logEn: 'MATCHING OPERATOR RECORD',
      badge: 'IDENTITY',
      action: 'confirm',
      btnZh: '確認身份',
      btnEn: 'CONFIRM IDENTITY',
      hold: 700,
      progress: 0.5
    },
    {
      zh: '接收訪問需求',
      en: 'ACCESS REQUEST RECEIVED',
      voiceMs: 1070,
      log: '接收訪問需求，建立會話通道…',
      logEn: 'HANDSHAKE ESTABLISHED',
      badge: 'REQUEST',
      action: 'auto',
      hold: 1200,
      progress: 0.66,
      assembleLogo: true
    },
    {
      zh: '開始讀取權限',
      en: 'PERMISSION READ STARTED',
      voiceMs: 1180,
      log: '開始讀取權限，索引工作區與會話…',
      logEn: 'READING PERMISSION SET',
      badge: 'READ',
      action: 'auto',
      hold: 1200,
      progress: 0.8,
      hex: true
    },
    {
      zh: '讀取完畢 · 核驗通過',
      en: 'READ COMPLETE · VERIFIED',
      voiceMs: 1700,
      log: '讀取完畢，核驗通過，權限已授予',
      logEn: 'PERMISSION AUTHORIZED',
      badge: 'VERIFY',
      action: 'auto',
      hold: 1600,
      progress: 0.93,
      reticle: true
    }
  ]
  /* DSHM: the closing line (line-6) was 3.73 s; kept as a silent beat too. */
  var WELCOME_VOICE_MS = 3730

  /* DSHM: whole run at speed 1, from page start to done() (measured in a
     network-blocked headless Chromium at 412x915: about 18.7 s). `seconds`
     scales the speed against this, inside the original 0.35x - 3x range. */
  var NATURAL_MS = 18700

  /* ── 配置 ──────────────────────────────────────────────────────────────
     DSHM: read from the URL query string (?name=&welcome=&number=&level=
     &seconds=&reduced=&skippable=&picture=&lang=). Extras: interact=1 keeps the
     original "tap the button to go on" on steps 1-2; theme=dark the dark plate. */
  function cfg() {
    var q = null
    try { q = new URLSearchParams(window.location.search) } catch (e) { q = null }
    function g(k) {
      try { return q ? (q.get(k) || '') : '' } catch (e) { return '' }
    }
    function flag(k, d) {
      var v = g(k)
      return v === '1' ? true : (v === '0' ? false : d)
    }
    function text(k, max) {
      var s = String(g(k)).replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u202a-\u202e\u2066-\u2069]/g, '').trim()
      var chars = Array.from ? Array.from(s) : s.split('')
      return chars.slice(0, max).join('')
    }
    var out = {
      theme: g('theme') === 'dark' ? 'dark' : 'light',
      speed: 1,
      requireInteraction: flag('interact', false),
      allowSkip: flag('skippable', true),
      reduced: flag('reduced', false),
      picture: flag('picture', false),
      identity: text('name', 24),
      welcome: text('welcome', 24) || '歡迎回來',
      identityId: text('number', 24) || 'No.0001',
      accessLevel: text('level', 24) || 'Lv.1',
      /* DSHM: brand text replaced (DeepSeek wordmarks are not shipped) */
      brand1: 'DSH',
      brand2: 'SYNTHESIZE INTELLIGENCE',
      brand3a: 'MOBILE',
      brand3b: 'OS',
      orgZh: 'DSH 行動版',
      orgEn: 'DSH MOBILE',
      orgSub: 'MOBILE OS',
      welcomeBox: 'DSH MOBILE',
      footer: 'POWERED BY DSH',
      accent: '#ff7500',
      glitchA: '#ff3b30',
      glitchB: '#2971b8'
    }
    var secs = Number(g('seconds'))
    if (isFinite(secs) && secs > 0) out.speed = NATURAL_MS / (secs * 1000)
    out.speed = Math.min(3, Math.max(0.35, Number(out.speed) || 1))
    return out
  }

  var C = cfg()

  /* DSHM: tell the app once that the animation is over. */
  var doneSent = false
  function signalDone() {
    if (doneSent) return
    doneSent = true
    try { if (window.DSHM && typeof window.DSHM.done === 'function') window.DSHM.done() } catch (e) { /* noop */ }
    try { window.location.hash = '#done' } catch (e) { /* noop */ }
  }

  /* 停滞看门狗：任意一幕卡住都要放行。每推进一幕重新上弦；等用户操作时 disarm。 */
  var STALL_MS = 15000
  var stallTimer = null
  function disarmStall() {
    if (stallTimer) { clearTimeout(stallTimer); stallTimer = null }
  }
  function armStall() {
    disarmStall()
    stallTimer = setTimeout(function () {
      if (finished) return
      finish()
    }, STALL_MS)
  }

  /* ── 小工具 ───────────────────────────────────────────────────────────── */
  function el(tag, cls, text) {
    var n = document.createElement(tag)
    if (cls) n.className = cls
    if (text != null) n.textContent = text
    return n
  }

  /* DSHM: replaces svgWhale(). With picture=1 it is the user's picture
     (picture.img, served by the app) inside a thin square frame; without one,
     or if the picture fails to load, the drawn FIGURE_PATH in currentColor. */
  var SVG_NS = 'http://www.w3.org/2000/svg'
  function svgFigure() {
    var svg = document.createElementNS(SVG_NS, 'svg')
    svg.setAttribute('viewBox', '0 0 50 50')
    svg.setAttribute('aria-hidden', 'true')
    var p = document.createElementNS(SVG_NS, 'path')
    p.setAttribute('d', FIGURE_PATH)
    p.setAttribute('fill-rule', 'nonzero')
    p.setAttribute('data-draw', '1')
    svg.appendChild(p)
    return svg
  }
  function makeMark(cls) {
    var box = el('div', 'dsu-mark' + (cls ? ' ' + cls : ''))
    if (!C.picture) {
      box.appendChild(svgFigure())
      return box
    }
    box.classList.add('dsu-mark-pic')
    var img = el('img', 'dsu-mark-img')
    img.alt = ''
    img.decoding = 'async'
    img.draggable = false
    img.addEventListener('error', function () {
      /* 拿不到图就退回画出来的人形 */
      box.classList.remove('dsu-mark-pic')
      box.textContent = ''
      box.appendChild(svgFigure())
    })
    img.src = 'picture.img'
    box.appendChild(img)
    var frame = document.createElementNS(SVG_NS, 'svg')
    frame.setAttribute('viewBox', '0 0 50 50')
    frame.setAttribute('aria-hidden', 'true')
    frame.setAttribute('class', 'dsu-mark-frame')
    var r = document.createElementNS(SVG_NS, 'rect')
    r.setAttribute('x', '0.55'); r.setAttribute('y', '0.55')
    r.setAttribute('width', '48.9'); r.setAttribute('height', '48.9')
    r.setAttribute('data-draw', '1')
    frame.appendChild(r)
    box.appendChild(frame)
    return box
  }

  /* DSHM: letter-spaced label under the mark (was "D E E P S E E K") */
  function spaced(s) {
    return (Array.from ? Array.from(String(s)) : String(s).split('')).filter(function (c) { return c !== ' ' }).join(' ')
  }

  /** 逐字渐显：比逐字截断更稳（居中排版不会因为字宽变化而抖动） */
  function typeIn(host, text, stagger) {
    host.textContent = ''
    var frag = document.createDocumentFragment()
    var n = 0
    for (var i = 0; i < text.length; i++) {
      var ch = text.charAt(i)
      if (ch === ' ') {
        frag.appendChild(document.createTextNode('\u00a0'))
        continue
      }
      var s = el('span', 'dsu-ch', ch)
      s.style.transitionDelay = Math.round(n * stagger) + 'ms'
      frag.appendChild(s)
      n++
    }
    host.appendChild(frag)
  }

  /* DSHM: phones are narrower than the desktop window this was drawn for.
     If a one-line text would run past the screen edge (16px gutter), scale its
     font down just enough; letter-spacing is in em, so the look is kept. */
  var fitted = []
  function fit(node) {
    if (!node) return
    if (fitted.indexOf(node) < 0) fitted.push(node)
    node.style.fontSize = ''
    var max = Math.max(120, (window.innerWidth || 412) - 32)
    var w = Math.max(node.getBoundingClientRect().width, node.scrollWidth || 0)
    if (w > max) {
      var fs = parseFloat(window.getComputedStyle(node).fontSize) || 16
      node.style.fontSize = (fs * max / w).toFixed(2) + 'px'
    }
  }
  window.addEventListener('resize', function () {
    for (var i = 0; i < fitted.length; i++) fit(fitted[i])
  })

  /* ── DOM 搭建 ─────────────────────────────────────────────────────────── */
  var root = document.getElementById(ROOT_ID)
  if (!root) {
    root = el('div')
    root.id = ROOT_ID
    root.className = 'dsu-root'
    document.body.appendChild(root)
  }
  root.classList.toggle('dsu-dark', C.theme === 'dark')
  root.style.setProperty('--dsu-speed', String(C.speed))
  root.style.setProperty('--dsu-accent', C.accent)
  root.style.setProperty('--dsu-glitch-a', C.glitchA)
  root.style.setProperty('--dsu-glitch-b', C.glitchB)

  var ui = {}
  var clockTimer = null
  var html =
    '<div class="dsu-plate"></div>' +
    '<div class="dsu-vignette"></div>' +
    '<div class="dsu-watermark"></div>' +
    '<div class="dsu-grain"></div>' +
    '<div class="dsu-black">' +
      '<div class="dsu-black-inner"></div>' +
      '<div class="dsu-sweep"></div>' +
    '</div>' +
    '<div class="dsu-wash"></div>' +
    '<header class="dsu-brand">' +
      '<div class="dsu-brand-1"></div><div class="dsu-brand-2"></div>' +
      '<div class="dsu-brand-3"><b></b><span></span></div>' +
    '</header>' +
    '<div class="dsu-stepcount">[ 00 / 06 ] INIT</div>' +
    '<div class="dsu-center">' +
      '<div class="dsu-status"><span class="dsu-status-zh"></span><i class="dsu-caret"></i></div>' +
      '<div class="dsu-sub-en"></div>' +
      '<div class="dsu-rule"></div>' +
      '<div class="dsu-logo"></div>' +
      '<div class="dsu-dots" hidden></div>' +
    '</div>' +
    '<div class="dsu-idline"></div>' +
    '<div class="dsu-reticle"></div>' +
    '<div class="dsu-hex"></div>' +
    '<div class="dsu-welcome">' +
      '<div class="dsu-w1">WELCOME TO</div>' +
      '<div class="dsu-w2"></div>' +
      '<div class="dsu-w3"></div>' +
      '<div class="dsu-w4"></div>' +
    '</div>' +
    '<div class="dsu-action">' +
      '<div class="dsu-card" hidden></div>' +
      '<button class="dsu-btn" type="button">' +
        '<span class="dsu-btn-zh"></span><span class="dsu-btn-en"></span>' +
      '</button>' +
      '<div class="dsu-hint"></div>' +
    '</div>' +
    '<div class="dsu-frame">' +
      '<i class="dsu-corner dsu-c-tl"></i><i class="dsu-corner dsu-c-tr"></i>' +
      '<i class="dsu-corner dsu-c-bl"></i><i class="dsu-corner dsu-c-br"></i>' +
      '<div class="dsu-scanline"></div>' +
    '</div>' +
    '<div class="dsu-slats"></div>' +
    '<div class="dsu-voice"><i></i><i></i><i></i><i></i><i></i></div>' +
    '<div class="dsu-meta"><span class="dsu-meta-1"></span><span class="dsu-meta-2"></span></div>' +
    '<div class="dsu-skip">SKIP ▸</div>' +
    '<footer class="dsu-foot"><span></span><i></i></footer>' +
    '<div class="dsu-subtitle">' +
      '<span class="dsu-sub-zh"></span><span class="dsu-sub-en2"></span>' +
    '</div>' +
    '<div class="dsu-progress"><i></i></div>' +
    '<div class="dsu-bloom"></div>' +
    '<div class="dsu-streak"></div>' +
    '<div class="dsu-flash"></div>'

  root.innerHTML = html

  function q(sel) { return root.querySelector(sel) }
  ui.plate = q('.dsu-plate')
  ui.watermark = q('.dsu-watermark')
  ui.black = q('.dsu-black')
  ui.blackInner = q('.dsu-black-inner')
  ui.sweep = q('.dsu-sweep')
  ui.wash = q('.dsu-wash')
  ui.flash = q('.dsu-flash')
  ui.bloom = q('.dsu-bloom')
  ui.streak = q('.dsu-streak')
  ui.vignette = q('.dsu-vignette')
  ui.brand = q('.dsu-brand')
  ui.center = q('.dsu-center')
  ui.brand1 = q('.dsu-brand-1')
  ui.brand2 = q('.dsu-brand-2')
  ui.brand3b = q('.dsu-brand-3 b')
  ui.brand3s = q('.dsu-brand-3 span')
  ui.stepcount = q('.dsu-stepcount')
  ui.status = q('.dsu-status')
  ui.statusZh = q('.dsu-status-zh')
  ui.caret = q('.dsu-caret')
  ui.subEn = q('.dsu-sub-en')
  ui.rule = q('.dsu-rule')
  ui.logo = q('.dsu-logo')
  ui.dots = q('.dsu-dots')
  ui.idline = q('.dsu-idline')
  ui.reticle = q('.dsu-reticle')
  ui.hex = q('.dsu-hex')
  ui.welcome = q('.dsu-welcome')
  ui.w1 = q('.dsu-w1')
  ui.w2 = q('.dsu-w2')
  ui.w3 = q('.dsu-w3')
  ui.w4 = q('.dsu-w4')
  ui.action = q('.dsu-action')
  ui.card = q('.dsu-card')
  ui.btn = q('.dsu-btn')
  ui.btnZh = q('.dsu-btn-zh')
  ui.btnEn = q('.dsu-btn-en')
  ui.hint = q('.dsu-hint')
  ui.skip = q('.dsu-skip')
  ui.foot = q('.dsu-foot')
  ui.footText = q('.dsu-foot span')
  ui.subZh = q('.dsu-sub-zh')
  ui.subEn2 = q('.dsu-sub-en2')
  ui.progress = q('.dsu-progress')
  ui.bar = q('.dsu-progress i')
  ui.frame = q('.dsu-frame')
  ui.scanline = q('.dsu-scanline')
  ui.slats = q('.dsu-slats')
  ui.voice = q('.dsu-voice')
  ui.meta = q('.dsu-meta')
  ui.meta1 = q('.dsu-meta-1')
  ui.meta2 = q('.dsu-meta-2')

  /* 内容填充 */
  ui.brand1.textContent = C.brand1
  ui.brand2.textContent = C.brand2
  ui.brand3b.textContent = C.brand3a
  ui.brand3s.textContent = C.brand3b
  ui.footText.textContent = C.footer
  ui.w2.textContent = C.welcomeBox
  /* DSHM: was '欢迎 ' + identity + ' 访问'; now the app's welcome line */
  ui.w4.textContent = C.identity ? C.welcome + '，' + C.identity : C.welcome
  ui.idline.textContent = '· ID CONFIRMED : ' + C.identityId.toUpperCase()

  ;(function blackStage() {
    var inner = ui.blackInner
    inner.appendChild(makeMark())
    inner.appendChild(el('div', 'dsu-black-zh', C.orgZh))
    var en = el('div', 'dsu-black-en', C.orgEn)
    inner.appendChild(en)
    inner.appendChild(el('div', 'dsu-black-sub', C.orgSub))
    ui.blackEn = en
  })()

  ;(function logoStage() {
    ui.logo.appendChild(makeMark())
    /* DSHM: label was 'D E E P S E E K' */
    ui.logo.appendChild(el('div', 'dsu-logolabel', spaced(C.identity || C.identityId).toUpperCase()))
  })()

  ;(function welcomeLogo() {
    ui.w3.appendChild(makeMark())
    /* DSHM: label was 'D E E P S E E K  ·  H A R N E S S' */
    ui.w3.appendChild(el('span', 'dsu-w3label', 'D S H  ·  M O B I L E'))
  })()

  ;(function watermark() {
    var boxes = [
      [0.60, 0.02, 0.16, 0.96], [0.78, 0.02, 0.16, 0.96], [0.96, 0.02, 0.16, 0.96],
      [0.05, 0.02, 0.10, 0.5]
    ]
    for (var i = 0; i < boxes.length; i++) {
      var b = boxes[i]
      var n = el('div', 'dsu-wm')
      n.style.left = (b[0] * 100) + '%'
      n.style.top = (b[1] * 100) + '%'
      n.style.width = (b[2] * 100) + '%'
      n.style.height = (b[3] * 100) + '%'
      ui.watermark.appendChild(n)
    }
  })()

  /* 左侧六格阶段柱 + 品牌块下方的小字数据行 + 实时时钟 */
  ;(function slatsAndMeta() {
    for (var i = 0; i < STEPS.length + 1; i++) ui.slats.appendChild(el('i', 'dsu-slat'))
    /* DSHM: was 'REV 0.1.0 · WEB PROFILE' */
    ui.meta1.textContent = 'REV 0.1.0 · MOBILE PROFILE'
    paintClock()
    clockTimer = setInterval(paintClock, 1000)
  })()

  ;(function reticle() {
    var svg = document.createElementNS(SVG_NS, 'svg')
    svg.setAttribute('viewBox', '0 0 400 400')
    function arc(cx, cy, r, a0, a1, cls, cap) {
      var rad = function (d) { return (d - 90) * Math.PI / 180 }
      var x0 = cx + r * Math.cos(rad(a0)), y0 = cy + r * Math.sin(rad(a0))
      var x1 = cx + r * Math.cos(rad(a1)), y1 = cy + r * Math.sin(rad(a1))
      var large = (a1 - a0) > 180 ? 1 : 0
      var p = document.createElementNS(SVG_NS, 'path')
      p.setAttribute('d', 'M' + x0.toFixed(2) + ' ' + y0.toFixed(2) +
        ' A' + r + ' ' + r + ' 0 ' + large + ' 1 ' + x1.toFixed(2) + ' ' + y1.toFixed(2))
      p.setAttribute('class', cls)
      if (cap) p.setAttribute('stroke-linecap', cap)
      return { node: p, x: x1, y: y1, x0: x0, y0: y0 }
    }
    var gA = document.createElementNS(SVG_NS, 'g')
    gA.setAttribute('class', 'dsu-ret-rot-a')
    /* 300° 弧，缺口留在正下方 6 点钟方向 */
    var a = arc(200, 200, 150, 215, 505, 'dsu-ring-a', 'round')
    gA.appendChild(a.node)
    var da = document.createElementNS(SVG_NS, 'circle')
    da.setAttribute('class', 'dsu-dot-a')
    da.setAttribute('cx', a.x.toFixed(2)); da.setAttribute('cy', a.y.toFixed(2)); da.setAttribute('r', '7')
    gA.appendChild(da)
    svg.appendChild(gA)

    var gB = document.createElementNS(SVG_NS, 'g')
    gB.setAttribute('class', 'dsu-ret-rot-b')
    var b = arc(200, 200, 104, 200, 490, 'dsu-ring-b', 'round')
    gB.appendChild(b.node)
    var db = document.createElementNS(SVG_NS, 'circle')
    db.setAttribute('class', 'dsu-dot-b')
    db.setAttribute('cx', b.x0.toFixed(2)); db.setAttribute('cy', b.y0.toFixed(2)); db.setAttribute('r', '4')
    gB.appendChild(db)
    svg.appendChild(gB)

    var gC = document.createElementNS(SVG_NS, 'g')
    var bl = arc(200, 200, 62, 118, 242, 'dsu-bracket', 'round')
    var br = arc(200, 200, 62, 298, 62, 'dsu-bracket', 'round')
    gC.appendChild(bl.node); gC.appendChild(br.node)
    svg.appendChild(gC)

    var pts = [[152, 132], [252, 268], [278, 150]]
    for (var i = 0; i < pts.length; i++) {
      var c = document.createElementNS(SVG_NS, 'circle')
      c.setAttribute('class', 'dsu-dot-o')
      c.setAttribute('cx', pts[i][0]); c.setAttribute('cy', pts[i][1]); c.setAttribute('r', '2.8')
      svg.appendChild(c)
    }
    ui.reticle.appendChild(svg)
  })()

  /* ── 时间线引擎 ───────────────────────────────────────────────────────── */
  var timers = []
  var waiters = []
  var skipped = false
  var finished = false
  var hexTimer = null

  function ms(base) { return Math.round(base / C.speed) }

  function wait(base) {
    return new Promise(function (resolve) {
      if (skipped) return resolve()
      var done = false
      function fin() {
        if (done) return
        done = true
        var i = waiters.indexOf(fin)
        if (i >= 0) waiters.splice(i, 1)
        resolve()
      }
      var t = setTimeout(fin, Math.max(0, ms(base)))
      timers.push(t)
      waiters.push(fin)
    })
  }

  function waitAction() {
    return new Promise(function (resolve) {
      if (skipped || !C.requireInteraction) return resolve()
      /* 等用户操作期间不上弦：正在犹豫的人不该被看门狗踢出去 */
      disarmStall()
      var done = false
      function fin() {
        if (done) return
        done = true
        cleanup()
        armStall()
        resolve()
      }
      function onKey(e) {
        if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') { e.preventDefault(); fin() }
      }
      /* DSHM: stopPropagation so this tap does not also count as "tap to skip" */
      function onClick(e) { e.preventDefault(); e.stopPropagation(); fin() }
      function cleanup() {
        ui.btn.removeEventListener('click', onClick)
        ui.action.removeEventListener('click', onClick)
        document.removeEventListener('keydown', onKey)
        var i = waiters.indexOf(fin)
        if (i >= 0) waiters.splice(i, 1)
      }
      ui.btn.addEventListener('click', onClick)
      ui.action.addEventListener('click', onClick)
      document.addEventListener('keydown', onKey)
      waiters.push(fin)
    })
  }

  function releaseAll() {
    for (var i = 0; i < timers.length; i++) clearTimeout(timers[i])
    timers.length = 0
    var list = waiters.slice()
    waiters.length = 0
    for (var j = 0; j < list.length; j++) { try { list[j]() } catch (err) { /* noop */ } }
  }

  function setProgress(p) {
    var pct = Math.max(0, Math.min(1, p))
    ui.bar.style.width = Math.round(pct * 100) + '%'
  }

  /** 六格阶段柱：走过的压暗、当前的拉满并点亮主题橙 */
  function setSlats(active) {
    var items = ui.slats.children
    for (var i = 0; i < items.length; i++) {
      var idx = i + 1
      items[i].classList.toggle('dsu-on', idx === active)
      items[i].classList.toggle('dsu-past', idx < active)
    }
  }

  /** 扫描线自上而下扫一遍（底板洗白后一次，验准时再一次） */
  function sweep() {
    if (!ui.scanline) return
    ui.scanline.classList.remove('dsu-go')
    void ui.scanline.offsetWidth
    ui.scanline.classList.add('dsu-go')
  }

  function paintClock() {
    var d = new Date()
    var pad = function (n) { return (n < 10 ? '0' : '') + n }
    ui.meta2.textContent = pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
  }

  function setSubtitle(zh, en) {
    ui.subZh.classList.remove('dsu-in')
    ui.subEn2.classList.remove('dsu-in')
    setTimeout(function () {
      ui.subZh.textContent = zh || ''
      ui.subEn2.textContent = en || ''
      ui.subZh.classList.add('dsu-in')
      if (en) ui.subEn2.classList.add('dsu-in')
    }, ms(140))
  }

  function setStepCount(i, code) {
    var totalSteps = STEPS.length + 1
    ui.stepcount.textContent = '[ ' + String(i).padStart(2, '0') + ' / ' +
      String(totalSteps).padStart(2, '0') + ' ] ' + code
  }

  function startHex() {
    var lines = 12
    function line() {
      var s = ''
      for (var i = 0; i < 26; i++) {
        s += '0123456789ABCDEF'.charAt(Math.floor(Math.random() * 16))
        if (i % 2 === 1) s += ' '
      }
      return s
    }
    function paint() {
      var out = []
      for (var i = 0; i < lines; i++) out.push(line())
      /* DSHM: textContent + white-space:pre instead of innerHTML '<br>' */
      ui.hex.textContent = out.join('\n')
    }
    paint()
    ui.hex.classList.add('dsu-in')
    hexTimer = setInterval(paint, 110)
  }
  function stopHex() {
    if (hexTimer) { clearInterval(hexTimer); hexTimer = null }
    ui.hex.classList.remove('dsu-in')
  }

  /* ── 声音 ──────────────────────────────────────────────────────────────
     DSHM: the original played recorded lines (third-party voice), sound
     effects and speech synthesis here. All of it is removed. What stays is
     the voice meter (the small orange bars bottom left) lit for as long as the
     original line lasted, and the timeline still waits for that length. */
  var voice = (function () {
    var meterTimer = null
    function setMeter(on) { ui.voice.classList.toggle('dsu-on', !!on) }
    return {
      /** 一句"台词"：resolve 原素材的时长(ms)，期间点亮声纹条 */
      line: function (lengthMs) {
        return new Promise(function (resolve) {
          if (finished || skipped) { resolve(0); return }
          var t = ms(lengthMs)
          var done = false
          function fin(v) {
            if (done) return
            done = true
            var i = waiters.indexOf(cancel)
            if (i >= 0) waiters.splice(i, 1)
            setMeter(false)
            resolve(v)
          }
          /* releaseAll()（跳过 / 收尾）时也要放行 */
          function cancel() { fin(0) }
          setMeter(true)
          if (meterTimer) clearTimeout(meterTimer)
          meterTimer = setTimeout(function () { meterTimer = null; fin(t) }, t)
          timers.push(meterTimer)
          waiters.push(cancel)
        })
      },
      stop: function () {
        if (meterTimer) { clearTimeout(meterTimer); meterTimer = null }
        setMeter(false)
      }
    }
  })()

  /* ── 时间线 ───────────────────────────────────────────────────────────── */
  function run() {
    armStall()
    return Promise.resolve()
      /* 第一幕：黑幕 --> 标识 */
      .then(function () { return wait(820) })
      .then(function () {
        ui.blackInner.classList.add('dsu-in')
        return wait(240)
      })
      .then(function () {
        ui.blackEn.classList.add('dsu-tighten')
        return wait(880)
      })
      .then(function () {
        ui.sweep.classList.add('dsu-go')
        return wait(760)
      })
      /* 过场：过曝洗白 */
      .then(function () {
        ui.blackInner.classList.add('dsu-out')
        ui.wash.style.transition = 'opacity ' + ms(220) + 'ms linear'
        ui.wash.style.opacity = '1'
        return wait(260)
      })
      .then(function () {
        ui.plate.style.transition = 'opacity ' + ms(900) + 'ms ease'
        ui.plate.style.opacity = '1'
        ui.watermark.style.transition = 'opacity ' + ms(1100) + 'ms ease'
        ui.watermark.style.opacity = '1'
        ui.wash.style.transition = 'opacity ' + ms(720) + 'ms cubic-bezier(.2,.9,.2,1)'
        ui.wash.style.opacity = '0'
        ui.black.style.transition = 'opacity ' + ms(720) + 'ms ease'
        ui.black.style.opacity = '0'
        return wait(760)
      })
      /* 第二幕：品牌块 / 页脚 / 进度条 */
      .then(function () {
        ui.brand.classList.add('dsu-in')
        ui.foot.classList.add('dsu-in')
        ui.stepcount.classList.add('dsu-in')
        ui.idline.classList.add('dsu-in')
        ui.frame.classList.add('dsu-in')
        ui.slats.classList.add('dsu-in')
        ui.meta.classList.add('dsu-in')
        ui.vignette.classList.add('dsu-in')
        ui.voice.classList.add('dsu-in')
        setProgress(0.12)
        setStepCount(0, 'BOOT')
        setSlats(0)
        setSubtitle('系統自檢中…', 'SYSTEM SELF-CHECK')
        if (C.allowSkip) ui.skip.classList.add('dsu-in')
        return wait(700)
      })
      .then(function () { ui.black.style.display = 'none'; sweep() })
      /* 六个阶段 */
      .then(function () { return stepLoop() })
      /* 终幕 */
      .then(function () { return finale() })
      .catch(function () { return finish() })
  }

  function stepLoop() {
    var chain = Promise.resolve()
    STEPS.forEach(function (step, i) {
      chain = chain.then(function () {
        if (finished) return
        armStall()
        var n = i + 1
        /* 身份读数只属于前两幕，第 3 幕起退场 */
        if (i >= 2) ui.idline.classList.remove('dsu-in')
        setStepCount(n, step.badge)
        setSlats(n)
        setProgress(step.progress)
        setSubtitle(step.log, step.logEn)
        /* "语音"在这里起头，但不阻塞下面的文字渐显 —— 两者同时发生 */
        var spoke = voice.line(step.voiceMs)

        ui.status.classList.toggle('dsu-zhline', true)
        /* 先摘掉 dsu-in 并强制一次样式计算，新的逐字 span 才会重新走渐显动画 */
        ui.status.classList.remove('dsu-in')
        ui.subEn.classList.remove('dsu-in')
        void ui.status.offsetWidth
        typeIn(ui.statusZh, step.zh, 46)
        typeIn(ui.subEn, step.en, 13)
        fit(ui.status)
        fit(ui.subEn)
        ui.caret.classList.toggle('dsu-on', !!step.action && step.action !== 'auto')
        requestAnimationFrame(function () {
          if (finished) return
          ui.status.classList.add('dsu-in')
          ui.subEn.classList.add('dsu-in')
        })
        ui.rule.classList.add('dsu-in')

        if (step.assembleLogo) assembleLogo()
        if (step.reticle) {
          /* 准星出现时中央只留状态字，标识和虚线收掉 */
          ui.logo.classList.remove('dsu-in')
          ui.dots.hidden = true
          ui.reticle.classList.add('dsu-in')
          sweep()
        }
        if (step.hex) startHex()

        /* 这一步停多久 = max(视觉基准, 台词时长 + 收尾留白) */
        function holdFor(spoken) {
          var target = Math.max(ms(step.hold), (spoken || 0) + ms(240))
          return wait(Math.max(0, target - (spoken || 0)))
        }

        if (step.action === 'grant' || step.action === 'confirm') {
          showAction(step)
          return wait(520).then(function () { return waitAction() }).then(function () {
            hideAction()
            ui.caret.classList.remove('dsu-on')
            return spoke
          }).then(function () { return wait(ms(320)) })
        }
        if (step.hex) {
          return spoke.then(holdFor).then(function () { stopHex() })
        }
        return spoke.then(holdFor)
      })
    })
    return chain
  }

  function assembleLogo() {
    ui.logo.classList.add('dsu-in')
    ui.dots.hidden = false
    var shape = ui.logo.querySelector('[data-draw]')
    var img = ui.logo.querySelector('.dsu-mark-img')
    if (shape && typeof shape.getTotalLength === 'function') {
      try {
        var len = shape.getTotalLength()
        if (img) img.style.opacity = '0'
        shape.style.fill = 'none'
        shape.style.stroke = 'currentColor'
        shape.style.strokeWidth = '1.1'
        shape.style.strokeDasharray = len
        shape.style.strokeDashoffset = len
        /* 参考片的"一笔画成"：先勾线，再落墨成面（有图时：先勾出相框，再把图显出来） */
        shape.animate(
          [{ strokeDashoffset: len }, { strokeDashoffset: 0 }],
          { duration: ms(1400), easing: 'cubic-bezier(.55,.06,.24,1)', fill: 'forwards' }
        )
        setTimeout(function () {
          if (img) {
            img.style.transition = 'opacity ' + ms(420) + 'ms ease'
            img.style.opacity = '1'
            return
          }
          shape.style.transition = 'fill ' + ms(420) + 'ms ease, stroke-width ' + ms(420) + 'ms ease'
          shape.style.fill = ''
          shape.style.strokeWidth = '0'
        }, ms(1450))
      } catch (err) { if (img) img.style.opacity = '1' }
    }
    return wait(300)
  }

  function showAction(step) {
    ui.btnZh.textContent = step.btnZh
    ui.btnEn.textContent = step.btnEn
    ui.card.hidden = step.action !== 'confirm'
    if (step.action === 'confirm') {
      ui.card.textContent = ''
      var rows = [
        ['身份名稱', C.identity || '訪客', true],
        ['身份編號', C.identityId, false],
        ['權限等級', C.accessLevel, false]
      ]
      rows.forEach(function (r) {
        var row = el('div', 'dsu-card-row')
        row.appendChild(el('span', null, r[0]))
        row.appendChild(el('b', r[2] ? 'dsu-zhname' : null, r[1]))
        ui.card.appendChild(row)
      })
    }
    /* DSHM: phone wording; only shown when the step really waits for a tap */
    ui.hint.textContent = C.requireInteraction ? '點一下按鈕繼續' : ''
    ui.action.classList.add('dsu-in')
    ui.btn.classList.add('dsu-pulse')
  }

  function hideAction() {
    ui.action.classList.remove('dsu-in')
    ui.btn.classList.remove('dsu-pulse')
  }

  function finale() {
    if (finished) return Promise.resolve()
    armStall()
    setStepCount(6, 'WELCOME')
    setSlats(6)
    setProgress(1)
    setSubtitle('身份驗證完成，會話已建立', 'IDENTITY VERIFIED · SESSION ESTABLISHED')
    var welcome = null

    ui.status.classList.remove('dsu-in')
    ui.subEn.classList.remove('dsu-in')
    ui.rule.classList.remove('dsu-in')
    ui.reticle.classList.remove('dsu-in')
    stopHex()
    /* 中央区整体退场：不清掉会和 WELCOME 版式叠字 */
    ui.center.classList.add('dsu-off')
    ui.dots.hidden = true

    return wait(360)
      .then(function () {
        ui.welcome.classList.add('dsu-in')
        ui.idline.classList.remove('dsu-in')
        /* 欢迎文案亮起的同一刻"开口"（终幕台词长度，静音） */
        welcome = voice.line(WELCOME_VOICE_MS)
        return wait(420)
      })
      .then(function () {
        /* 结尾色散故障：分片位移 + 0.5px 模糊 + 混合模式 */
        var targets = [ui.w1, ui.w2, ui.w4]
        targets.forEach(function (t) {
          t.classList.add('dsu-glitch')
          t.setAttribute('data-text', t.textContent)
        })
        ui.w2.classList.add('dsu-glitch-box')
        void ui.welcome.offsetWidth
        targets.forEach(function (t) { t.classList.add('dsu-on') })
        return wait(640)
      })
      /* 光晕从中心炸开 + 变形光条横扫 + 文字青色 halation */
      .then(function () {
        root.classList.add('dsu-peak')
        ui.bloom.classList.add('dsu-go')
        ui.streak.classList.add('dsu-go')
        return wait(430)
      })
      .then(function () {
        ui.flash.style.transition = 'opacity ' + ms(70) + 'ms linear'
        ui.flash.style.opacity = '0.72'
        return wait(90)
      })
      .then(function () {
        ui.flash.style.transition = 'opacity ' + ms(340) + 'ms cubic-bezier(0.4, 0, 0.2, 1)'
        ui.flash.style.opacity = '0'
        return wait(320)
      })
      /* 欢迎语"没念完"就不揭幕 */
      .then(function () { return welcome })
      /* 揭幕：整个机位后撤 + 轻微失焦 */
      .then(function () {
        root.classList.add('dsu-handoff')
        return wait(780)
      })
      .then(function () { return finish() })
  }

  function finish() {
    if (finished) return
    finished = true
    disarmStall()
    releaseAll()
    stopHex()
    voice.stop()
    if (clockTimer) { clearInterval(clockTimer); clockTimer = null }
    root.classList.remove('dsu-boot')
    root.classList.add('dsu-gone')
    setTimeout(function () {
      if (root && root.parentNode) root.parentNode.removeChild(root)
      signalDone()
    }, C.reduced ? 700 : ms(900))
  }

  function requestSkip() {
    if (skipped || finished) return
    skipped = true
    releaseAll()
    finish()
  }

  /* DSHM: reduced=1 — one still frame (the plate with the welcome), then fade */
  function still() {
    root.classList.add('dsu-still')
    ui.black.style.display = 'none'
    ui.wash.style.display = 'none'
    ui.plate.style.opacity = '1'
    ui.watermark.style.opacity = '1'
    ;[ui.brand, ui.foot, ui.stepcount, ui.frame, ui.slats, ui.meta, ui.vignette, ui.voice].forEach(function (n) {
      n.classList.add('dsu-in')
    })
    if (C.allowSkip) ui.skip.classList.add('dsu-in')
    setProgress(1)
    setStepCount(6, 'WELCOME')
    setSlats(6)
    ui.subZh.textContent = '身份驗證完成，會話已建立'
    ui.subEn2.textContent = 'IDENTITY VERIFIED · SESSION ESTABLISHED'
    ui.subZh.classList.add('dsu-in')
    ui.subEn2.classList.add('dsu-in')
    ui.center.classList.add('dsu-off')
    ui.welcome.classList.add('dsu-in')
    var t = setTimeout(finish, 1500)
    timers.push(t)
  }

  /* DSHM: SKIP label, Esc, and (when skippable) a tap anywhere */
  if (C.allowSkip && ui.skip) {
    ui.skip.hidden = false
    ui.skip.addEventListener('click', function (e) { e.stopPropagation(); requestSkip() })
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') requestSkip()
    })
    document.addEventListener('click', function () { requestSkip() })
  } else if (ui.skip) {
    ui.skip.hidden = true
  }

  setProgress(0.05)
  ;[ui.blackEn, ui.blackInner.querySelector('.dsu-black-zh'), ui.w1, ui.w2, ui.w4].forEach(fit)
  root.classList.remove('dsu-boot')

  if (C.reduced) still()
  else run()
})()
