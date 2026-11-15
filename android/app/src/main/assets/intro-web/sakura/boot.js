/* [DSH Mobile] Adapted from KylinQ01/dsh-startup-animation @ 3a8c57a8fe2113b81ff861e9fc4589ad9bae6460,
   assets/boot.js. MIT License, Copyright (c) 2026 KylinQ01 (see LICENSE). Every change is listed in NOTICE.md and
   marked "[DSH Mobile]" below. The scene, its timings and its random particle recipes are the original's.

   What changed, in short: the parameters come from this page's query string instead of the dsh host
   (window.__dshsConfig); the two pictures are the user's own picture.img (or a code-drawn stand-in); the greeting is
   the user's welcome words; the exit is timed by `seconds` instead of waiting for the dsh home screen; at the end it
   calls window.DSHM.done() and sets location.hash = '#done'. Removed: the sessionStorage "played in the last 60 s"
   mark, the dsh home screen's module assembly, the eco/fancy tiers and the birthday easter egg.

   DSH 启动动画（页面侧）。宿主插件把它作为 /dsh-startup/boot.js 提供，并在 </body> 前以 defer 注入。
   启动页 DOM 全部由本文件创建，收尾时自行清理；任何异常都会立刻把主界面放出来，绝不留白屏。

   这里只负责"编排"：搭 DOM、给星尘/光点撒随机参数、跟随指针做视差、等图落地后入场、
   判定主界面就绪、一次切类名完成过场，最后把节点和启动页样式删干净。
   所有位移/缩放/透明度都写在 boot.css 里，且只跑 transform / translate / opacity 这类合成器属性。 */
(function () {
  'use strict'

  var doc = document
  var html = doc.documentElement
  if (window.__dshsStarted || !doc.body) return
  window.__dshsStarted = true

  // [DSH Mobile] Whatever happens, the app hears "done" once: at the end of the exit, on any error, or from this
  // last-resort timer (8 s longest `seconds` + 1.2 s picture wait + 0.7 s exit, with room to spare).
  var signalled = false
  function signalDone() {
    if (signalled) return
    signalled = true
    try { if (window.DSHM && typeof window.DSHM.done === 'function') window.DSHM.done() } catch (err) {}
    try { location.hash = '#done' } catch (err) {}
  }
  setTimeout(signalDone, 14000)

  var P = readParams()

  // [DSH Mobile] The original's MIN_MS / GRACE_MS / MAX_MS waited for the dsh home screen to mount. Here the whole
  // animation lasts `seconds` (1.5–8, default 3): the scene holds for `seconds` minus the exit, but never less than
  // its entrance needs: the whole greeting has landed and stays fully shown for READ_MS before the exit starts
  // (see riseDelay below; with the original's 4-character greeting the last character lands at 1.06 s).
  var OUT_MS = 700     // 过场时长，与 boot.css 的 .is-out 保持一致
  var CALM_OUT_MS = 200  // [DSH Mobile] the reduced-motion exit (boot.css: transition-duration .2s)
  var MIN_HOLD_MS = 1100
  var READ_MS = 300    // [DSH Mobile] how long the whole greeting is fully shown before the exit starts

  // [DSH Mobile] The greeting's per-character rise, as in boot.css: character i starts at FIRST_S + i × STEP_S and
  // takes RISE_S. The original's greeting had 4 characters; the user's words have up to 24. If the original step
  // would leave the last ones still rising when the exit starts, the step after the 4th character shrinks just
  // enough (not below MIN_STEP_S), and the hold grows only when even that does not fit.
  var FIRST_S = 0.2
  var STEP_S = 0.08
  var MIN_STEP_S = 0.02
  var RISE_S = 0.62    // boot.css: .dshs-hello span transform .62s

  /* [DSH Mobile] Festival easter eggs by the phone's date, as the original's default "auto" mode did
     (dsh host: lib/index.js autoFestival). An optional `festival=off|sakura|snow|newyear` picks one by hand.
     The birthday egg is gone: its date lived in the dsh host's config. */
  var FESTIVAL = pickFestival(P.festival, new Date())

  /* 画质档位决定"撒多少东西"。[DSH Mobile] Only the original's default tier, "standard", is kept. */
  var STARS = 16
  var PETALS = 10
  var SPARKS = 8
  /* 节日粒子在档位之外单独算，且**不许低于标准档的量**。
     樱花不再复用"上浮光点"：那是圆形柔光，染成粉色只会像气泡；
     真正的樱花瓣是另一套粒子（花瓣形状 + 边落边翻，见 scatterSakura）。 */
  var SAKURA = FESTIVAL === 'sakura' ? PETALS + 10 : 0
  var SNOW = FESTIVAL === 'snow' ? SPARKS + 14 : 0
  if (FESTIVAL === 'sakura' || FESTIVAL === 'snow') PETALS = 0   // 花瓣/雪自己会飘，圆光点就别来凑热闹了
  if (FESTIVAL === 'newyear') SPARKS = SPARKS + 6
  var FEST_PARTICLES = SAKURA + SNOW                              // 有节日粒子才建承载层

  // [DSH Mobile] The greeting is the user's welcome words; without them, the original's 欢迎回来 / 新年快乐 in
  // Traditional Chinese. The status line is the original's, in Traditional Chinese.
  var HELLO = P.welcome !== '' ? P.welcome : (FESTIVAL === 'newyear' ? '新年快樂' : '歡迎回來')
  var TIP = '正在準備你的工作台'

  /* [DSH Mobile] Where the user's words may wrap. Each character is its own inline-block, so that it can rise on
     its own, and a browser may wrap between any two inline-blocks. So build() puts the characters that ordinary
     text keeps on one line (a Latin word, a number, a character and the punctuation that must not start or end a
     line) in one run, and leaves open only the places where text wraps anyway: at spaces, and around Han, kana,
     Hangul and emoji. Spaces stay real spaces between the runs. */
  var SPACE = /^\s+$/
  var WIDE = /[\p{sc=Han}\p{sc=Hiragana}\p{sc=Katakana}\p{sc=Hangul}\p{sc=Bopomofo}\u2e80-\u2fff\u3001-\u303f\u3200-\u33ff\ufe30-\ufe4f\uff00-\uffef\p{Extended_Pictographic}]/u
  var NO_START = /^[)\]},.!?:;%…‥、。，．！？：；）］｝〕〉》」』】〙〗〟｠・ー～〜ゝゞヽヾぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ]/u
  var NO_END = /^[(\[{（［｛〔〈《「『【〘〖〝｟]/u
  var CHARS = graphemes(HELLO)
  var LETTERS = 0      // characters that rise (spaces do not)
  for (var c = 0; c < CHARS.length; c++) if (!SPACE.test(CHARS[c])) LETTERS++

  // 系统开了"减少动态效果"就只保留入场与过场，不做常驻循环与视差。
  // [DSH Mobile] The app decides with `reduced=1|0`; without it, the system's media query decides.
  // html.dshs-calm replaces the original's @media block in boot.css.
  var calm = P.reduced
  if (calm === null) {
    calm = false
    try { calm = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) } catch (err) {}
  }
  html.classList.toggle('dshs-calm', calm)
  if (FESTIVAL !== '') html.classList.add('dshs-fest-' + FESTIVAL)

  var OUT = calm ? CALM_OUT_MS : OUT_MS
  // [DSH Mobile] Reduced motion is a short still version: at most 2 s in all.
  var TOTAL_MS = (calm ? Math.min(P.seconds, 2) : P.seconds) * 1000
  var HOLD_MS = Math.max(calm ? 800 : MIN_HOLD_MS, TOTAL_MS - OUT)
  // [DSH Mobile] Fit the greeting into the hold (see FIRST_S above). Calm mode shows every character at once.
  var LATE_STEP_S = STEP_S
  if (!calm && LETTERS > 4) {
    var room = (HOLD_MS - READ_MS) / 1000 - RISE_S - riseDelay(3)
    LATE_STEP_S = Math.min(STEP_S, Math.max(MIN_STEP_S, room / (LETTERS - 4)))
  }
  if (!calm && LETTERS > 0) {
    HOLD_MS = Math.max(HOLD_MS, Math.ceil((riseDelay(LETTERS - 1) + RISE_S) * 1000) + READ_MS)
  }

  var enteredAt = 0
  var splash = null
  var bar = null
  var poll = 0
  var raf = 0
  var px = 0
  var py = 0

  try {
    splash = build()
    doc.body.appendChild(splash)
    bar = splash.querySelector('.dshs-bar span')
    follow()
    whenImagesSettled(enter)
  } catch (err) {
    // [DSH Mobile] The original released the dsh page here; this page has nothing under it, so it just ends.
    if (splash && splash.parentNode) splash.parentNode.removeChild(splash)
    splash = null
    signalDone()
  }

  /** [DSH Mobile] Reads the query string the app opens this page with. Text is length-capped and only ever
   *  written with textContent, never parsed as HTML. */
  function readParams() {
    var q = null
    try { q = new URLSearchParams(location.search) } catch (err) {}
    function get(key) {
      var value = q ? q.get(key) : null
      return typeof value === 'string' ? value : ''
    }
    function text(key) {
      // control characters out, trimmed, at most 24 characters (the app's own limit)
      return graphemes(get(key).replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/g, ' ').trim()).slice(0, 24).join('').trim()
    }
    var seconds = parseFloat(get('seconds'))
    if (!isFinite(seconds)) seconds = 3
    var reduced = get('reduced')
    return {
      name: text('name'),
      welcome: text('welcome'),
      seconds: Math.min(8, Math.max(1.5, seconds)),
      reduced: reduced === '1' ? true : (reduced === '0' ? false : null),
      skippable: get('skippable') !== '0',
      picture: get('picture') === '1',
      festival: get('festival'),
    }
  }

  /** [DSH Mobile] User-perceived characters, so an emoji or an accented letter is never cut in half. */
  function graphemes(value) {
    try {
      if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
        var out = []
        for (var part of new Intl.Segmenter('zh-Hant', { granularity: 'grapheme' }).segment(value)) out.push(part.segment)
        return out
      }
    } catch (err) {}
    return Array.from(value)
  }

  /** [DSH Mobile] When the greeting's character number i starts to rise, in seconds (see FIRST_S). */
  function riseDelay(i) {
    return i < 4 ? FIRST_S + i * STEP_S : FIRST_S + 3 * STEP_S + (i - 3) * LATE_STEP_S
  }

  /** [DSH Mobile] Whether the greeting may wrap between two characters (see WIDE above). */
  function canBreak(before, after) {
    return (WIDE.test(before) || WIDE.test(after)) && !NO_START.test(after) && !NO_END.test(before)
  }

  /** 跟着日期走的节日窗口。新年那几天优先于飘雪（1 月 1~3 日两个窗口重叠）。[DSH Mobile] from lib/index.js. */
  function pickFestival(asked, date) {
    if (asked === 'off') return ''
    if (asked === 'sakura' || asked === 'snow' || asked === 'newyear') return asked
    var day = (date.getMonth() + 1) * 100 + date.getDate()
    if (day >= 101 && day <= 103) return 'newyear'
    if (day >= 320 && day <= 420) return 'sakura'
    if (day >= 1201 || day <= 215) return 'snow'
    return ''
  }

  function build() {
    var box = doc.createElement('div')
    box.id = 'dshs'
    // [DSH Mobile] The original hid the splash from screen readers (the dsh page under it was the content).
    // Here it is the whole page, so it reads as one picture with the greeting.
    box.setAttribute('role', 'img')
    box.setAttribute('aria-label', HELLO + (P.name !== '' ? '，' + P.name : ''))
    var pings = ''
    for (var p = 0; p < 2; p++) pings += '<div class="dshs-ping"></div>'
    // [DSH Mobile] Only constant markup goes through innerHTML. The pictures and every piece of text are added
    // below with createElement / textContent.
    box.innerHTML =
      // 背景单独一层，且刻意不留富余：盒子就是视口
      '<div class="dshs-bg"></div>' +
      // 远景装饰：极光色块 + 星尘 + 上浮光点 + 掠过流星 + 斜向光带
      '<div class="dshs-world">' +
        '<div class="dshs-aurora"><i></i><i></i></div>' +
        '<div class="dshs-stars"></div>' +
        '<div class="dshs-petals"></div>' +
        // 节日粒子共用一个承载层：它必须是铺满视口的盒子（inset: 0）
        (FEST_PARTICLES > 0 ? '<div class="dshs-fest"></div>' : '') +
        '<div class="dshs-streaks"><i></i><i></i></div>' +
        '<div class="dshs-sheen"></div>' +
      '</div>' +
      // 中景：提亮面纱 + 收尾时顶上来的"壁纸白纱" + 跟随指针的暖光 + 暗角
      '<div class="dshs-veil"></div>' +
      '<div class="dshs-handoff"></div>' +
      '<div class="dshs-cursor"></div>' +
      '<div class="dshs-vig"></div>' +
      // 近景：头像 + 柔光 + 两圈转动的加载环 + 一次性爆闪 + 两圈涟漪 + 迸出的星火 + 过场闪白
      '<div class="dshs-flash"></div>' +
      '<div class="dshs-stage">' +
        '<div class="dshs-portrait">' +
          '<div class="dshs-halo"></div>' +
          '<div class="dshs-orbit"></div>' +
          '<div class="dshs-orbit"></div>' +
          '<div class="dshs-burst"></div>' +
          pings +
          '<div class="dshs-sparks"></div>' +
          '<div class="dshs-avatar"><span class="dshs-shine"></span></div>' +
        '</div>' +
        '<div class="dshs-hello"></div>' +
        '<div class="dshs-accent"></div>' +
        '<div class="dshs-tip"><i>.</i><i>.</i><i>.</i></div>' +
        '<div class="dshs-bar"><span></span><i class="dshs-orb"></i><i class="dshs-orb"></i></div>' +
      '</div>'

    // [DSH Mobile] Background and avatar: the user's picture (served by the app as picture.img) in both places,
    // as the original showed its two pictures; without one, the code-drawn stand-ins.
    var bg = box.querySelector('.dshs-bg')
    var avatar = box.querySelector('.dshs-avatar')
    if (P.picture) {
      var bgImg = doc.createElement('img')
      bgImg.alt = ''
      bgImg.src = 'picture.img'
      bg.appendChild(bgImg)
      var faceImg = doc.createElement('img')
      faceImg.alt = ''
      faceImg.src = 'picture.img'
      avatar.insertBefore(faceImg, avatar.firstChild)
      // If the picture cannot be read, fall back exactly as if there were none.
      bgImg.addEventListener('error', function () { plainBackground(bg) }, { once: true })
      faceImg.addEventListener('error', function () { plainAvatar(avatar) }, { once: true })
    } else {
      plainBackground(bg)
      plainAvatar(avatar)
    }

    // 逐字上浮：一个字一个 span。[DSH Mobile] textContent per character. boot.css staggers the first four by
    // nth-child (.2 s + 80 ms each); here every character gets its delay from riseDelay() (the same values for the
    // first four), because the runs below change which child a character is. Spaces are plain text between the
    // runs, so they keep their width and are where the line wraps.
    var hello = box.querySelector('.dshs-hello')
    var run = null     // the run the previous character is in, if it is in one
    var last = null    // the previous character's span
    var k = 0          // the rising character's number
    for (var i = 0; i < CHARS.length; i++) {
      var ch = CHARS[i]
      if (SPACE.test(ch)) {
        hello.appendChild(doc.createTextNode(ch))
        run = null
        last = null
        continue
      }
      var span = doc.createElement('span')
      span.textContent = ch
      if (!calm) span.style.transitionDelay = riseDelay(k).toFixed(3) + 's'
      k++
      if (last && !canBreak(last.textContent, ch)) {
        if (!run) {
          run = doc.createElement('b')
          run.className = 'dshs-word'
          hello.replaceChild(run, last)
          run.appendChild(last)
        }
        run.appendChild(span)
      } else {
        run = null
        hello.appendChild(span)
      }
      last = span
    }
    var tip = box.querySelector('.dshs-tip')
    tip.insertBefore(doc.createTextNode(TIP), tip.firstChild)

    scatterStars(box.querySelector('.dshs-stars'))
    scatterPetals(box.querySelector('.dshs-petals'))
    scatterSparks(box.querySelector('.dshs-sparks'))
    if (SAKURA > 0) scatterSakura(box.querySelector('.dshs-fest'))
    if (SNOW > 0) scatterSnow(box.querySelector('.dshs-fest'))
    return box
  }

  /** [DSH Mobile] The background without a picture: a plain light ground drawn by boot.css. */
  function plainBackground(bg) {
    var img = bg.querySelector('img')
    if (img) bg.removeChild(img)
    bg.classList.add('is-plain')
  }

  /** [DSH Mobile] The avatar without a picture: the name's first character, or a five-petal blossom. */
  function plainAvatar(avatar) {
    var img = avatar.querySelector('img')
    if (img) avatar.removeChild(img)
    if (avatar.querySelector('.dshs-mono')) return
    var mono = doc.createElement('span')
    mono.className = 'dshs-mono'
    var first = P.name !== '' ? graphemes(P.name)[0] : ''
    if (first !== '' && /[\p{L}\p{N}]/u.test(first)) mono.textContent = first
    else mono.appendChild(blossom())
    avatar.insertBefore(mono, avatar.firstChild)
  }

  /** [DSH Mobile] Five petals round a small heart, drawn by boot.css in the original's own sakura-petal colours. */
  function blossom() {
    var bloom = doc.createElement('span')
    bloom.className = 'dshs-bloom'
    bloom.setAttribute('aria-hidden', 'true')
    for (var k = 0; k < 5; k++) bloom.appendChild(doc.createElement('i'))
    bloom.appendChild(doc.createElement('b'))
    return bloom
  }

  /** 星尘：大小/亮度/闪烁周期都随机，闪烁相位用负延迟错开，避免整屏同步。 */
  function scatterStars(host) {
    if (calm) return
    for (var i = 0; i < STARS; i++) {
      var size = 2 + Math.random() * 2.6
      var star = doc.createElement('i')
      star.className = 'dshs-star'
      star.style.cssText =
        'left:' + (Math.random() * 100).toFixed(2) + '%;' +
        'top:' + (Math.random() * 100).toFixed(2) + '%;' +
        'width:' + size.toFixed(1) + 'px;' +
        'height:' + size.toFixed(1) + 'px;' +
        '--dshs-tw:' + (0.45 + Math.random() * 0.5).toFixed(2) + ';' +
        'animation-duration:' + (2.4 + Math.random() * 3.6).toFixed(1) + 's;' +
        'animation-delay:' + (-Math.random() * 6).toFixed(1) + 's;'
      host.appendChild(star)
    }
  }

  /**
   * 星火：入场那一下从头像四周迸出去。角度按数量均分再抖动一点，
   * 半径/延迟/大小随机，所以每次开软件的形状都不一样。
   */
  function scatterSparks(host) {
    if (calm) return
    for (var i = 0; i < SPARKS; i++) {
      var size = 3 + Math.random() * 3
      var spark = doc.createElement('i')
      spark.className = 'dshs-spark'
      spark.style.cssText =
        'width:' + size.toFixed(1) + 'px;' +
        'height:' + size.toFixed(1) + 'px;' +
        '--dshs-sa:' + (i * (360 / SPARKS) + Math.random() * 18 - 9).toFixed(1) + 'deg;' +
        '--dshs-sr:' + (58 + Math.random() * 46).toFixed(0) + 'px;' +
        '--dshs-sd:' + (0.22 + Math.random() * 0.28).toFixed(2) + 's;'
      host.appendChild(spark)
    }
  }

  /** 光点：位置、大小、上浮时长、左右摆动都随机，每次开软件都不一样。 */
  function scatterPetals(host) {
    for (var i = 0; i < PETALS; i++) {
      var size = 5 + Math.random() * 9
      var petal = doc.createElement('i')
      petal.className = 'dshs-petal'
      petal.style.cssText =
        'left:' + (Math.random() * 100).toFixed(2) + '%;' +
        'width:' + size.toFixed(1) + 'px;' +
        'height:' + size.toFixed(1) + 'px;' +
        'animation-duration:' + (9 + Math.random() * 9).toFixed(1) + 's;' +
        'animation-delay:' + (-Math.random() * 12).toFixed(1) + 's;' +
        '--dshs-sway:' + (Math.random() * 12 - 6).toFixed(1) + 'vmin;'
      host.appendChild(petal)
    }
  }

  /**
   * 樱花瓣（春季彩蛋）：**不是圆点**——花瓣形状靠不规则的圆角切出来，大小/翻转速/飘摆都随机，
   * 边落边左右打摆。
   */
  function scatterSakura(host) {
    if (calm) return
    for (var i = 0; i < SAKURA; i++) {
      var size = 7 + Math.random() * 7
      var petal = doc.createElement('i')
      petal.className = 'dshs-sakura'
      petal.style.cssText =
        'left:' + (Math.random() * 100).toFixed(2) + '%;' +
        'width:' + size.toFixed(1) + 'px;' +
        'height:' + (size * 1.15).toFixed(1) + 'px;' +
        // 两种朝向的尖角，混着撒才不像复制粘贴
        'border-radius:' + (Math.random() < 0.5 ? '100% 0 100% 0' : '0 100% 0 100%') + ';' +
        'animation-duration:' + (9 + Math.random() * 7).toFixed(1) + 's;' +
        'animation-delay:' + (-Math.random() * 12).toFixed(1) + 's;' +
        '--dshs-sway:' + (2 + Math.random() * 5).toFixed(1) + 'vmin;' +
        '--dshs-spin:' + (Math.random() * 420 - 210).toFixed(0) + 'deg;'
      host.appendChild(petal)
    }
  }

  /** 飘雪（冬季彩蛋）：从上往下落，横向慢慢偏，和"上浮光点"正好反向。 */
  function scatterSnow(host) {
    if (calm) return
    for (var i = 0; i < SNOW; i++) {
      var size = 5 + Math.random() * 6
      var flake = doc.createElement('i')
      flake.className = 'dshs-snow'
      flake.style.cssText =
        'left:' + (Math.random() * 100).toFixed(2) + '%;' +
        'width:' + size.toFixed(1) + 'px;' +
        'height:' + size.toFixed(1) + 'px;' +
        'animation-duration:' + (8 + Math.random() * 7).toFixed(1) + 's;' +
        'animation-delay:' + (-Math.random() * 12).toFixed(1) + 's;' +
        '--dshs-drift:' + (Math.random() * 10 - 5).toFixed(1) + 'vmin;' +
        '--dshs-spin:' + (Math.random() * 360).toFixed(0) + 'deg;'
      host.appendChild(flake)
    }
  }

  /**
   * 指针视差：只把归一化坐标写进 --dshs-px / --dshs-py，各层在 CSS 里按自己的深度换算成位移。
   * 每帧最多写一次，且只改自定义属性，不触碰布局。触屏拖动不参与（会抖）。
   * [DSH Mobile] Kept as is: on a phone it only wakes for a mouse or a stylus.
   */
  function follow() {
    if (calm) return
    window.addEventListener('pointermove', function (event) {
      if (event.pointerType === 'touch') return
      var w = window.innerWidth || 1
      var h = window.innerHeight || 1
      px = (event.clientX / w) * 2 - 1
      py = (event.clientY / h) * 2 - 1
      // 动过指针才点亮跟随暖光：没动过时它会在正中照出一团白，反而糊了背景
      if (splash) splash.classList.add('is-aim')
      if (raf || !splash) return
      raf = window.requestAnimationFrame(paint)
    }, { passive: true })

    function paint() {
      raf = 0
      if (!splash) return
      splash.style.setProperty('--dshs-px', px.toFixed(3))
      splash.style.setProperty('--dshs-py', py.toFixed(3))
    }
  }

  /** 图都落地（或出错、或 1.2s 超时）后入场，避免图片"跳"进画面。 */
  function whenImagesSettled(done) {
    var imgs = splash.querySelectorAll('img')
    var left = imgs.length
    var fired = false
    // [DSH Mobile] Without a picture there is nothing to wait for (the original always had two).
    if (left === 0) { fired = true; done(); return }
    function one() {
      if (fired) return
      if (--left > 0) return
      fired = true
      done()
    }
    for (var i = 0; i < imgs.length; i++) {
      if (imgs[i].complete) { one(); continue }
      imgs[i].addEventListener('load', one, { once: true })
      imgs[i].addEventListener('error', one, { once: true })
    }
    setTimeout(function () { if (!fired) { fired = true; done() } }, 1200)
  }

  function enter() {
    if (!splash) return
    // [DSH Mobile] Make sure the "before" styles were computed, so the entrance transitions run even when
    // nothing had to load first.
    void splash.offsetWidth
    splash.classList.add('is-in')
    enteredAt = Date.now()
    // 点一下或按任意键都能跳过等待。[DSH Mobile] only when the app says skippable=1 (the default).
    if (P.skippable) {
      splash.addEventListener('click', finish)
      window.addEventListener('keydown', finish)
    }
    poll = setInterval(check, 80)
  }

  function progress(value) {
    if (bar) bar.style.transform = 'scaleX(' + value.toFixed(3) + ')'
  }

  /** [DSH Mobile] The original waited for the dsh home screen (#root, a textarea) between 1.5 s and 5 s, filling the
   *  bar to 92 % over the first 1.5 s. Here the bar fills to 92 % over the hold, and the hold ends the scene. */
  function check() {
    var elapsed = Date.now() - enteredAt
    progress(Math.min(0.92, elapsed / HOLD_MS * 0.92))
    if (elapsed >= HOLD_MS) finish()
  }

  function finish() {
    if (!splash) return
    var node = splash
    splash = null
    clearInterval(poll)
    poll = 0
    if (raf) { window.cancelAnimationFrame(raf); raf = 0 }
    window.removeEventListener('keydown', finish)
    progress(1)
    node.classList.add('is-out')
    html.classList.remove('dshs-boot')
    html.classList.add('dshs-boot-out')
    // [DSH Mobile] Removed: the sessionStorage "played in the last 60 s" mark (the app decides when to play).

    // 等淡出**真的**结束再摘节点：以 transitionend 为准，定时器只做兜底。
    var done = false
    function cleanup() {
      if (done) return
      done = true
      if (node.parentNode) node.parentNode.removeChild(node)
      signalDone()   // [DSH Mobile] tell the app the animation is over
    }
    node.addEventListener('transitionend', function (event) {
      if (event.target === node && event.propertyName === 'opacity') cleanup()
    })
    setTimeout(cleanup, OUT + 600)

    // [DSH Mobile] Removed: assemble(), the dsh home screen's "module assembly" (it moved the dsh page's own
    // panels, which are not in this bundle).
  }
})()
