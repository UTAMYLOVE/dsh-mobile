/*
 * 粉色霓虹敘事 (neon): a DSH Mobile startup look.
 *
 * Adapted from wsy304450907-boop/Z1ro2 @ 397742bb37c976d6f4531689f2854f8cc6e2392b,
 * file `.preview/client.template.js` (dsh plugin "dsh-neon-splash-v3" 0.2.0).
 * MIT License, Copyright (c) 2026 Z1ro2. See LICENSE and NOTICE.md in this folder.
 *
 * The original is a React component that the dsh desktop app mounts in its shell overlay. This file plays the same
 * stages with the same elements, class names, timings and canvas drawing as plain DOM code, so the page needs no
 * framework and nothing from dsh. The Chinese comments are the original author's. Every change is marked
 * [DSH Mobile]. The sound engine, the voice clip, the desktop pet, the settings page and the debug probes are not here
 * (see NOTICE.md).
 */
(function () {
  'use strict'

  // ─────────────────────────── [DSH Mobile] page contract ───────────────────────────

  /** Control, zero-width and bidi characters are taken out of the text parameters. */
  const CONTROL = /[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff]/g

  /** User-perceived characters, so an emoji or an accented letter is never cut in half. */
  function graphemes(value) {
    try {
      if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
        const out = []
        for (const part of new Intl.Segmenter('zh-Hant', { granularity: 'grapheme' }).segment(value)) out.push(part.segment)
        return out
      }
    } catch (_noSegmenter) { /* fall through */ }
    return Array.from(value)
  }

  /** ?name=&welcome=&number=&level=&seconds=&reduced=0|1&skippable=0|1&picture=1|0&lang=zh-TW */
  function readParams() {
    let query = null
    try { query = new URLSearchParams(window.location.search) } catch (_noQuery) { query = null }
    const get = (key) => {
      const value = query ? query.get(key) : null
      return typeof value === 'string' ? value : ''
    }
    const text = (key, max) => graphemes(get(key).replace(CONTROL, ' ').trim()).slice(0, max).join('').trim()
    const seconds = parseFloat(get('seconds'))
    const reduced = get('reduced')
    return {
      name: text('name', 24),
      welcome: text('welcome', 24),
      number: text('number', 16),
      level: text('level', 16),
      seconds: Number.isFinite(seconds) && seconds > 0 ? seconds : null,
      reduced: reduced === '1' ? true : (reduced === '0' ? false : null),
      skippable: get('skippable') !== '0',
      picture: get('picture') === '1',
    }
  }

  /** Tells the app once that the animation is over. */
  let doneSent = false
  function signalDone() {
    if (doneSent) return
    doneSent = true
    try {
      if (window.DSHM && typeof window.DSHM.done === 'function') window.DSHM.done()
    } catch (_hostGone) { /* nothing to tell */ }
    try { window.location.hash = '#done' } catch (_noHash) { /* nothing to tell */ }
  }
  // A script error must never leave the app waiting.
  window.addEventListener('error', () => signalDone())
  window.addEventListener('unhandledrejection', () => signalDone())

  /** The user's picture, served by the app next to this page. */
  const PICTURE_PATH = 'picture.img'
  /**
   * [DSH Mobile] With picture=1 the app also serves avatar.img: a 512x512 square already cut around the face in the
   * picture. The small round picture uses it; the blurred full-screen background stays picture.img.
   */
  const AVATAR_PATH = 'avatar.img'

  /** Loads an image once and remembers the answer, so a missing file is asked for only once. */
  const imageProbes = new Map()
  function imageReady(path) {
    if (!imageProbes.has(path)) {
      imageProbes.set(path, new Promise((resolve) => {
        if (typeof Image === 'undefined') {
          resolve(false)
          return
        }
        const probe = new Image()
        probe.decoding = 'async'
        probe.onload = () => resolve(true)
        probe.onerror = () => resolve(false)
        probe.src = path
      }))
    }
    return imageProbes.get(path)
  }
  function pictureReady() {
    return imageReady(PICTURE_PATH)
  }
  /**
   * [DSH Mobile] The file for the small round picture: avatar.img, or picture.img if avatar.img fails to load, or
   * null if both fail (then no picture is shown there, as before).
   */
  function avatarSource() {
    return imageReady(AVATAR_PATH).then((ok) => ok ? AVATAR_PATH : pictureReady().then((ok2) => ok2 ? PICTURE_PATH : null))
  }

  // ─────────────────────────── [DSH Mobile] tiny DOM helpers ───────────────────────────

  /** Sets a React-style style object: camelCase properties and `--custom` properties. */
  function setStyle(node, style) {
    if (!style) return
    for (const key of Object.keys(style)) {
      const value = style[key]
      if (key.startsWith('--')) node.style.setProperty(key, String(value))
      else node.style[key] = value
    }
  }

  /** Same shape as React.createElement for the props this file uses. Text always goes in as a text node. */
  function h(tag, props) {
    const node = document.createElement(tag)
    if (props) {
      for (const key of Object.keys(props)) {
        const value = props[key]
        if (value === null || value === undefined || value === false) continue
        if (key === 'className') node.className = value
        else if (key === 'style') setStyle(node, value)
        else node.setAttribute(key, String(value))
      }
    }
    for (let index = 2; index < arguments.length; index += 1) append(node, arguments[index])
    return node
  }
  function append(node, child) {
    if (child === null || child === undefined || child === false) return
    if (Array.isArray(child)) {
      for (const item of child) append(node, item)
      return
    }
    if (typeof child === 'string') node.appendChild(document.createTextNode(child))
    else if (child.node instanceof Node) node.appendChild(child.node)
    else node.appendChild(child)
  }

  /** Collects timers, listeners and frames so one call clears them (React's effect cleanup). */
  function scope() {
    const offs = []
    return {
      timeout(fn, ms) {
        const id = window.setTimeout(fn, ms)
        offs.push(() => window.clearTimeout(id))
        return id
      },
      interval(fn, ms) {
        const id = window.setInterval(fn, ms)
        offs.push(() => window.clearInterval(id))
        return id
      },
      on(target, type, fn, options) {
        target.addEventListener(type, fn, options)
        offs.push(() => target.removeEventListener(type, fn, options))
      },
      add(off) { offs.push(off) },
      dispose() {
        for (const off of offs.splice(0)) {
          try { off() } catch (_ignored) { /* already gone */ }
        }
      },
    }
  }

  /** Mounts a component object ({ node, mounted?, destroy? }) and runs its effects once it is in the document. */
  function mounted(part) {
    if (part && typeof part.mounted === 'function') part.mounted()
    return part
  }

  // ─────────────────────────── 原作常量（original constants） ───────────────────────────

  const SCENE_LAUNCH = 'launch'
  const SCENE_RESTORE = 'restore'
  /** 精简模式下的时长倍率（恢复窗口触发时更短）。 */
  const COMPACT_SCALE = 0.3
  /** 精简模式下要保证的最小睁眼时长（毫秒）。 */
  const MIN_OPEN_MS = 120
  /**
   * 睁眼曲线里「完全保持闭合」的比例：这段时间眼皮几乎不动、画面全黑
   * （观感上就是"缓慢地眨眼/缓缓睁眼"的前半段），之后才开始张开并同步变亮。
   */
  const LID_HOLD = 0.22

  // ─────────────────────── 主色 ───────────────────────

  /** 默认主色（粉色）。 */
  const DEFAULT_ACCENT = '#ff3d84'

  /** 把 `#rgb` / `#rrggbb` 解析成 `{ r, g, b }`；解析不了返回 null。 */
  function parseAccentColor(value) {
    const text = String(value ?? '').trim().replace(/^#/, '')
    const expanded = text.length === 3 ? text.split('').map((ch) => ch + ch).join('') : text
    if (!/^[0-9a-fA-F]{6}$/.test(expanded)) return null
    return {
      r: parseInt(expanded.slice(0, 2), 16),
      g: parseInt(expanded.slice(2, 4), 16),
      b: parseInt(expanded.slice(4, 6), 16),
    }
  }

  /** 规范化成 `#rrggbb`；非法值回落到默认粉。 */
  function normalizeAccentColor(value) {
    const rgb = parseAccentColor(value)
    if (rgb === null) return DEFAULT_ACCENT
    return `#${[rgb.r, rgb.g, rgb.b].map((part) => part.toString(16).padStart(2, '0')).join('')}`
  }

  /** 感知亮度 0..1：用来决定该用暗色还是亮色做文字/底衬。 */
  function accentLuminance(value) {
    const rgb = parseAccentColor(value)
    if (rgb === null) return 0.5
    return (0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b) / 255
  }

  /**
   * canvas 用：从主色向白色插值。
   * @param accent - 主色（`#rrggbb`）。
   * @param whiten - 0 = 主色，1 = 纯白。
   * @returns `[r, g, b]`
   */
  function accentToWhite(accent, whiten) {
    const rgb = parseAccentColor(accent) ?? parseAccentColor(DEFAULT_ACCENT)
    const amount = Math.min(1, Math.max(0, Number(whiten) || 0))
    return [
      Math.round(rgb.r + (255 - rgb.r) * amount),
      Math.round(rgb.g + (255 - rgb.g) * amount),
      Math.round(rgb.b + (255 - rgb.b) * amount),
    ]
  }

  /**
   * 从当前主色推导出整套配色变量。
   *
   * `--dshs-accent-rgb` 是关键：所有带透明度的强调色（辉光、渐变、描边）都写成
   * `rgba(var(--dshs-accent-rgb), α)`，所以换一个值，整个动画的强调色**一起**变。
   */
  function buildAccentVars(accentValue, deepValue) {
    const accent = normalizeAccentColor(accentValue)
    const rgb = parseAccentColor(accent)
    const light = accentLuminance(accent) >= 0.6
    const deep = normalizeAccentColor(
      typeof deepValue === 'string' && deepValue !== '' ? deepValue : accent,
    )
    /** 把某个通道往 target（0 或 255）混，得到同色系的深浅变体。 */
    const mix = (target, amount) => `#${[rgb.r, rgb.g, rgb.b]
      .map((channel) => Math.round(channel + (target - channel) * amount))
      .map((part) => part.toString(16).padStart(2, '0')).join('')}`
    return {
      '--dshs-accent-rgb': `${rgb.r},${rgb.g},${rgb.b}`,
      '--dshs-pink': light ? mix(0, 0.12) : accent,
      '--dshs-pink-soft': light ? accent : mix(255, 0.58),
      '--dshs-pink-glow': `rgba(${rgb.r},${rgb.g},${rgb.b},.45)`,
      /** 文字/图标用的强调色（带足够对比度）。 */
      '--dshs-accent-ink': light ? mix(0, 0.2) : mix(255, 0.24),
      /** 阴影/体积感用的深色。 */
      '--dshs-accent-deep': deep,
    }
  }

  // ─────────────────────────── 配置默认值 ───────────────────────────

  /**
   * 与 host 半侧 Config 对齐的兜底默认值。
   * [DSH Mobile] Only the fields the animation reads are kept. The sound, voice, pet, play-mode and colour-picker
   * fields belonged to the dropped settings page and sound engine.
   */
  const FALLBACK = {
    timeSource: 'system',
    periods: {
      noon: { start: '11:00', end: '17:00' },
      dusk: { start: '17:00', end: '20:00' },
      night: { start: '20:00', end: '05:00' },
    },
    compactOnRestore: true,
    timings: {
      blackScreen: 900,
      /** 开屏：高度模糊 → 逐渐清晰（同时由暗到亮）。 */
      open: 3400,
      /** 终端第一行：自动逐字打出 `running Deepseek Harness`。 */
      boot: 2200,
      /** 终端第二行：正在连接工作区模型（带进度条）。 */
      connect: 1600,
      /** 终端第三段：工作区预载，各插件 0 → 100。 */
      loading: 7000,
      /** 边框浮出后的停顿（代码里固定为 120ms）。 */
      glassesHold: 120,
      /** 登录动画：用户名与密码逐字打出（**登录之前**，不延长）。 */
      login: 2600,
      /** 代码 → HUD（打字 + 编译）。 */
      braindance: 6400,
      /** HUD 由中心向四周展开。 */
      hudExpand: 3000,
      /** 文字 + 署名的入场。 */
      hudText: 5200,
      /** 代码弹幕：涨满再变稀。 */
      particles: 6000,
      /** 遮罩渐隐 / 主界面渐显。 */
      fadeOut: 4000,
    },
    /**
     * 「正在连接工作区模型 xxx」里显示的模型名。
     * [DSH Mobile] The original showed a DeepSeek model id here. A neutral placeholder takes its place.
     */
    model: 'dsh/default',
    accent: DEFAULT_ACCENT,
    accentDeep: '#c2185b',
    /** 戴上眼镜后把身后场景压暗的程度：1 全亮，0.55 默认，0 全黑。 */
    braindanceLight: 0.55,
    rainDensity: 1,
    /** 代码流弹幕的密度倍率。 */
    codeRain: 1.4,
    /** 数码海浪的密度倍率：0.2 最稀，1 默认，2 最密。 */
    waveIntensity: 1,
    timeScale: 1,
    allowSkip: true,
  }

  // ─────────────────────────── 时段判定 ───────────────────────────

  /** 把 `HH:MM` 解析成 0..1439 的分钟数；无法解析时返回 null。 */
  function parseClock(text) {
    const match = /^\s*(\d{1,2})\s*[:：]\s*(\d{1,2})\s*$/.exec(String(text ?? ''))
    if (match === null) return null
    const hours = Number(match[1])
    const minutes = Number(match[2])
    if (hours > 23 || minutes > 59) return null
    return hours * 60 + minutes
  }

  /** 某个分钟数是否落在一个时段里（支持跨午夜；start === end 视为整天）。 */
  function inPeriod(clock, start, end) {
    const from = parseClock(start)
    const to = parseClock(end)
    if (from === null || to === null || from === to) return true
    if (from < to) return clock >= from && clock < to
    return clock >= from || clock < to
  }

  /**
   * 按配置解析当前时段。
   * @returns `noon` | `dusk` | `night`
   */
  function resolvePeriod(now, config) {
    const forced = config.timeSource
    if (forced === 'noon' || forced === 'dusk' || forced === 'night') return forced
    const clock = now.getHours() * 60 + now.getMinutes()
    for (const key of ['noon', 'dusk', 'night']) {
      const period = config.periods[key]
      if (inPeriod(clock, period.start, period.end)) return key
    }
    const nightStart = parseClock(config.periods.night.start)
    if (nightStart !== null) {
      const distance = Math.min(Math.abs(clock - nightStart), 1440 - Math.abs(clock - nightStart))
      if (distance <= 360) return 'night'
    }
    return 'dusk'
  }

  // ─────────────────────────── 时间线 ───────────────────────────

  /**
   * 算出本次播放的实际时长并生成绝对时刻表。（unchanged from the original）
   * @param config - 合并后的配置。
   * @param scene - 触发场景。
   */
  function buildTimeline(config, scene) {
    const compact = scene === SCENE_RESTORE && Boolean(config.compactOnRestore)
    const scale = (compact ? COMPACT_SCALE : 1) * (Number(config.timeScale) || 1)
    const ms = (value, fallback) => {
      const base = Number.isFinite(Number(value)) ? Number(value) : fallback
      return Math.max(0, Math.round(base * scale))
    }
    const msFloor = (value, fallback, floor) => {
      const scaled = ms(value, fallback)
      return compact ? Math.max(scaled, Math.round(floor * (Number(config.timeScale) || 1))) : scaled
    }

    const blackScreen = ms(config.timings.blackScreen, FALLBACK.timings.blackScreen)
    const requestedOpen = ms(config.timings.open, FALLBACK.timings.open)
    const open = compact ? Math.max(requestedOpen, MIN_OPEN_MS) : Math.max(200, requestedOpen)
    const lidHoldRatio = LID_HOLD
    const brightenAt = blackScreen + Math.round(open * lidHoldRatio)

    /** 边框浮出之后的停顿：**固定压到 120ms**（衔接要尽可能紧）。 */
    const glassesHold = msFloor(120, 120, 100)
    const boot = msFloor(config.timings.boot, FALLBACK.timings.boot, 900)
    const connect = msFloor(config.timings.connect, FALLBACK.timings.connect, 800)
    const loading = msFloor(config.timings.loading, FALLBACK.timings.loading, 1600)
    const login = msFloor(config.timings.login, FALLBACK.timings.login, 1500)
    const braindance = msFloor(config.timings.braindance, FALLBACK.timings.braindance, 1700)
    const hudExpand = msFloor(config.timings.hudExpand, FALLBACK.timings.hudExpand, 520)
    const hudText = msFloor(config.timings.hudText, FALLBACK.timings.hudText, 900)
    const particles = ms(config.timings.particles, FALLBACK.timings.particles)
    const fadeOut = ms(config.timings.fadeOut, FALLBACK.timings.fadeOut)

    const openAt = blackScreen
    const bootAt = openAt + open
    const glassesAt = bootAt + boot + connect
    const loadingAt = glassesAt + glassesHold
    const loginAt = loadingAt + loading
    const bdAt = loginAt + login
    const hudAt = bdAt + braindance
    const textAt = hudAt + hudExpand
    const particlesAt = textAt + hudText
    /**
     * 代码弹幕的密度包络：先越来越密铺满屏幕，再从满屏逐列退回 0。
     * 下降段（fall）同时就是**主界面逐渐显出来**的阶段。
     */
    const rainRise = Math.max(500, Math.round(particles * 0.55))
    const rainFall = Math.max(1600, Math.min(6000, particles + Math.round(fadeOut * 0.6)))
    const rainSpan = rainRise + rainFall
    /** 揭幕跑完后再留一点点余量，避免动画没结束就被卸载（会显得生硬）。 */
    const REVEAL_TAIL_MS = 220
    const endAt = particlesAt + rainSpan + REVEAL_TAIL_MS
    const fadeAt = Math.max(particlesAt + rainRise, endAt - fadeOut)

    const timeline = [
      { kind: 'open', at: openAt },
      { kind: 'boot', at: bootAt },
      { kind: 'connect', at: bootAt + boot },
      { kind: 'glasses', at: glassesAt },
      { kind: 'loading', at: loadingAt },
      { kind: 'login', at: loginAt },
      /** 预载完成的时刻：切到 AUTH 状态（界面不变），并**停在这里等用户点击**。 */
      { kind: 'loginwait', at: bdAt },
      { kind: 'braindance', at: bdAt },
      { kind: 'hud', at: hudAt },
      { kind: 'text', at: textAt },
      { kind: 'particles', at: particlesAt },
      { kind: 'fade', at: fadeAt },
      { kind: 'done', at: endAt },
    ]

    return {
      timeline,
      durations: {
        compact,
        blackScreen,
        open,
        lidHoldRatio,
        brightenAt,
        boot,
        connect,
        loading,
        glassesHold,
        login,
        braindance,
        hudExpand,
        hudText,
        particles,
        fadeOut,
        rainRise,
        rainFall,
        rainSpan,
        endAt,
      },
    }
  }

  // ─────────────────────────── 雨 ───────────────────────────

  /**
   * 玻璃上的雨：细密雨丝 + 挂在玻璃上的水珠 + 偶尔滑落的水痕。
   * @param props.density - 雨量倍率（0 关闭，1 默认，3 暴雨）。
   * @param props.compact - 精简模式（雨量减半）。
   */
  function Rain(props) {
    const density = Math.max(0, Number(props.density) || 0)
    const compact = Boolean(props.compact)
    if (density <= 0) return null
    const canvas = h('canvas', { className: 'dshsplash-rain', 'data-compact': String(compact), 'aria-hidden': 'true' })
    const life = scope()

    const start = () => {
      const context = canvas.getContext('2d')
      if (context === null) return
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      let width = 0
      let height = 0
      const resize = () => {
        const box = canvas.parentElement
        width = (box?.clientWidth ?? window.innerWidth) || window.innerWidth
        height = (box?.clientHeight ?? window.innerHeight) || window.innerHeight
        canvas.width = Math.max(1, Math.floor(width * dpr))
        canvas.height = Math.max(1, Math.floor(height * dpr))
        canvas.style.width = `${width}px`
        canvas.style.height = `${height}px`
      }
      resize()
      window.addEventListener('resize', resize)

      const factor = (compact ? 0.5 : 1) * density
      const meter = (width * height) / (1920 * 1080)
      const streakCount = Math.round(520 * meter * factor)
      const dropletCount = Math.round(70 * meter * factor)

      const streak = () => ({
        x: Math.random() * width,
        y: Math.random() * height - height,
        len: 14 + Math.random() * 46,
        speed: 620 + Math.random() * 780,
        alpha: 0.1 + Math.random() * 0.26,
        width: 0.6 + Math.random() * 1.1,
        wobble: (Math.random() - 0.5) * 12,
        phase: Math.random() * Math.PI * 2,
      })
      const droplet = () => ({
        x: Math.random() * width,
        y: Math.random() * height,
        r: 0.8 + Math.random() * 2.4,
        alpha: 0.14 + Math.random() * 0.3,
        speed: 4 + Math.random() * 16,
        tail: 0,
        wobble: (Math.random() - 0.5) * 6,
      })

      const streaks = new Array(Math.max(0, streakCount)).fill(null).map(streak)
      const droplets = new Array(Math.max(0, dropletCount)).fill(null).map(droplet)

      let frame = 0
      let last = 0
      let stopped = false

      const draw = (timestamp) => {
        if (stopped) return
        const delta = last === 0 ? 16 : Math.min(48, timestamp - last)
        last = timestamp
        const seconds = delta / 1000
        context.setTransform(dpr, 0, 0, dpr, 0, 0)
        context.clearRect(0, 0, width, height)
        context.lineCap = 'round'

        // 白底画面上的雨丝偏冷灰，避免像纯白噪点
        for (const drop of streaks) {
          drop.y += drop.speed * seconds
          drop.phase += seconds * 3
          if (drop.y - drop.len > height) Object.assign(drop, streak(), { y: -drop.len })
          const drift = Math.sin(drop.phase) * drop.wobble
          context.strokeStyle = `rgba(120,140,170,${drop.alpha.toFixed(3)})`
          context.lineWidth = drop.width
          context.beginPath()
          context.moveTo(drop.x + drift, drop.y)
          context.lineTo(drop.x + drift - 2.4, drop.y - drop.len)
          context.stroke()
        }

        for (const drop of droplets) {
          drop.y += drop.speed * seconds
          drop.wobble += seconds * 0.6
          if (drop.y - drop.r > height) Object.assign(drop, droplet(), { y: -drop.r })
          const x = drop.x + Math.sin(drop.wobble) * 1.6
          if (drop.tail > 0) {
            const gradient = context.createLinearGradient(x, drop.y - drop.tail, x, drop.y)
            gradient.addColorStop(0, 'rgba(140,158,186,0)')
            gradient.addColorStop(1, `rgba(140,158,186,${(drop.alpha * 0.5).toFixed(3)})`)
            context.strokeStyle = gradient
            context.lineWidth = drop.r * 0.9
            context.beginPath()
            context.moveTo(x, drop.y - drop.tail)
            context.lineTo(x, drop.y)
            context.stroke()
          }
          context.fillStyle = `rgba(255,255,255,${(drop.alpha * 0.85).toFixed(3)})`
          context.beginPath()
          context.ellipse(x, drop.y, drop.r * 0.72, drop.r, 0, 0, Math.PI * 2)
          context.fill()
          context.strokeStyle = `rgba(130,150,180,${(drop.alpha * 0.6).toFixed(3)})`
          context.lineWidth = 0.6
          context.stroke()
        }

        frame = window.requestAnimationFrame(draw)
      }

      frame = window.requestAnimationFrame(draw)
      life.add(() => {
        stopped = true
        window.cancelAnimationFrame(frame)
        window.removeEventListener('resize', resize)
      })
    }

    return { node: canvas, mounted: start, destroy: () => life.dispose() }
  }

  // ─────────────────────────── 屏幕边缘边框 ───────────────────────────

  /**
   * 屏幕边缘边框：四条贴边压条（上黑 / 下粉 / 左右黑到粉）+ 一圈粉色镜片环。
   * [DSH Mobile] The bottom-right tag showed the author's handle; it shows the user's `number` (or DSH).
   */
  function EdgeFrame(props) {
    return {
      node: h('div', { className: 'dshsplash-frame-edge', 'data-role': 'edge-frame', 'aria-hidden': 'true' },
        h('i', { className: 'top' }),
        h('i', { className: 'bottom' }),
        h('i', { className: 'left' }),
        h('i', { className: 'right' }),
        h('div', { className: 'dshsplash-lens-ring' }),
        h('b', { className: 'tl' }, 'AR'),
        h('b', { className: 'tr' }, 'OPTICS'),
        h('b', { className: 'bl' }, 'LINK 98.2%'),
        h('b', { className: 'br' }, props.tag),
        h('div', { className: 'dshsplash-frame-sweep' }),
      ),
    }
  }

  // ─────────────────────────── 代码终端 ───────────────────────────

  /**
   * 逐字打出的「开机脚本」——它就是后面 HUD 的由来。
   * [DSH Mobile] `subject` is the user's name (the original typed the author's handle); the comment is in
   * Traditional Chinese.
   */
  function bootCodeLines(subject) {
    return [
      { text: '// neon-splash · boot sequence', cls: 'cmt' },
      { text: 'const optics = await boot({', cls: '' },
      { text: `  subject: ${JSON.stringify(subject)},`, cls: '' },
      { text: '  profile: "ar-hud",', cls: '' },
      { text: '})', cls: '' },
      { text: 'optics.mount(HUD)   // → 編譯介面', cls: 'kw' },
      { text: 'optics.run()', cls: 'kw' },
    ]
  }

  /**
   * 代码终端：先逐字打出开机脚本，再「执行」——终端收起、粉色冲击环扩散，
   * 由 HUD 展开接管（对应「先写代码，执行后变成 HUD」）。
   * @param props.duration - 本段总时长（毫秒），打字节奏由它推算。
   */
  function CodeTerminal(props) {
    const duration = Math.max(400, Number(props.duration) || 2600)
    const spec = bootCodeLines(props.subject)
    // [DSH Mobile] typed by code point, so a name with an emoji is never cut in half
    const full = Array.from(spec.map((line) => line.text).join('\n'))
    const life = scope()
    const caret = h('span', { className: 'caret' }, '\u00A0')
    const code = h('pre', { className: 'dshsplash-terminal-code' }, caret)
    const node = h('div', { className: 'dshsplash-terminal', 'data-compiling': 'false', 'data-role': 'code-terminal' },
      h('div', { className: 'dshsplash-terminal-bar' },
        h('i', null), h('i', null), h('i', { className: 'pink' }),
        h('span', null, 'neon-splash — boot.ts'),
      ),
      code,
    )
    const spans = []
    const render = (count) => {
      const lines = full.slice(0, count).join('').split('\n')
      lines.forEach((text, index) => {
        let span = spans[index]
        if (span === undefined) {
          span = h('span', { className: (spec[index] ?? { cls: '' }).cls })
          spans[index] = span
          code.insertBefore(span, caret.parentNode === code ? caret : null)
        }
        span.textContent = `${text}${index < lines.length - 1 ? '\n' : ''}`
      })
      if (count >= full.length && caret.parentNode === code) caret.remove()
    }
    render(0)

    const start = () => {
      const typeMs = Math.max(200, Math.round(duration * 0.68))
      const step = Math.max(8, Math.round(typeMs / Math.max(1, full.length)))
      let index = 0
      const timer = life.interval(() => {
        index += 1
        render(index)
        if (index >= full.length) window.clearInterval(timer)
      }, step)
      life.timeout(() => {
        node.setAttribute('data-compiling', 'true')
        append(node, [
          h('div', { className: 'dshsplash-compile' }),
          h('div', { className: 'dshsplash-compile delay' }),
          h('div', { className: 'dshsplash-compile-ring' }),
        ])
      }, typeMs)
    }
    return { node, mounted: start, destroy: () => life.dispose() }
  }

  // ─────────────────────────── 粒子与代码雨 ───────────────────────────

  /** 代码雨用的字符集：十六进制 + JS 片段 + 少量片假名。 */
  const CODE_GLYPHS = '0123456789ABCDEF{}[]()=><;:+-*/.const let await=>async return optic hud pink アカサタナハマヤラワ'

  /**
   * 收尾的代码弹幕：**全屏密铺、上下交错**的代码串。
   * @param props.codeRain - 密度倍率（0 关闭）。
   * @param props.rise / props.fall - 密度包络的上升 / 下降时长。
   */
  function ParticleField(props) {
    const canvas = h('canvas', { className: 'dshsplash-particles', 'aria-hidden': 'true' })
    const codeRain = Math.max(0, Number(props.codeRain) || 0)
    /** 主色：canvas 拿不到 CSS 变量，由 props 传进来。 */
    const accentRgb = parseAccentColor(props.accent) ?? parseAccentColor(DEFAULT_ACCENT)
    const rise = Math.max(200, Number(props.rise) || 1200)
    const fall = Math.max(200, Number(props.fall) || 2500)
    const life = scope()

    const start = () => {
      const context = canvas.getContext('2d')
      if (context === null) return
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      let width = 0
      let height = 0
      const resize = () => {
        width = window.innerWidth
        height = window.innerHeight
        canvas.width = Math.max(1, Math.floor(width * dpr))
        canvas.height = Math.max(1, Math.floor(height * dpr))
        canvas.style.width = `${width}px`
        canvas.style.height = `${height}px`
      }
      resize()
      window.addEventListener('resize', resize)

      const columnWidth = Math.max(12, Math.round(13 / Math.max(0.6, codeRain)))
      const laneCount = Math.max(0, Math.round(width / columnWidth))
      const lanes = []
      for (let index = 0; index < laneCount; index += 1) {
        // 列与列的进度错开，避免所有车道同时出现在同一高度
        const up = index % 2 === 1
        const cells = 14 + Math.floor(Math.random() * 22)
        const size = (10 + Math.random() * 5) * Math.max(0.85, Math.min(1.7, codeRain))
        lanes.push({
          x: index * columnWidth + columnWidth * 0.5 + (Math.random() - 0.5) * 3,
          up,
          speed: 190 + Math.random() * 470,
          head: Math.random() * (height + 900) - 450,
          cells,
          size,
          lineHeight: size + 5,
          alpha: Math.min(0.96, (0.42 + Math.random() * 0.4) * Math.max(0.7, Math.min(1.5, codeRain))),
          accentLane: Math.random() < 0.24,
          glyphs: new Array(cells).fill(null).map(() => CODE_GLYPHS[Math.floor(Math.random() * CODE_GLYPHS.length)]),
          swap: 0,
          /** 这条车道在包络里的「点亮阈值」（0..1，沿 0→1 打散）。 */
          appearAt: ((index + Math.random() * 0.85) / laneCount) * 0.92,
        })
      }
      // 总字符数上限，防止 4K 屏上格子太多拖慢帧率
      const totalCells = lanes.reduce((sum, lane) => sum + lane.cells, 0)
      const cellBudget = 4200 * Math.max(0.6, Math.min(1.6, codeRain))

      let frame = 0
      let startedAt = 0
      let last = 0
      let stopped = false

      /** 平滑：0→1 之间做 ease-in-out，避免包络折线生硬。 */
      const smooth = (value) => {
        const x = Math.min(1, Math.max(0, value))
        return x * x * (3 - 2 * x)
      }

      const draw = (timestamp) => {
        if (stopped) return
        if (startedAt === 0) startedAt = timestamp
        const elapsed = timestamp - startedAt
        const delta = last === 0 ? 16 : Math.min(48, timestamp - last)
        last = timestamp
        const seconds = delta / 1000
        context.setTransform(dpr, 0, 0, dpr, 0, 0)
        context.clearRect(0, 0, width, height)

        /** 密度包络（0..1）：rise 段 0 → 1，fall 段 1 → 0。 */
        const envelope = elapsed <= rise
          ? smooth(elapsed / rise)
          : smooth(1 - (elapsed - rise) / fall)
        const lit0 = 0.08
        const strength = Math.max(0, (envelope - lit0) / (1 - lit0))
        // 全屏代码弹幕：相邻列反向，上下交错对穿
        context.textBaseline = 'top'
        const glyphPace = totalCells > cellBudget ? cellBudget / totalCells : 1
        for (const lane of lanes) {
          lane.head += (lane.up ? -1 : 1) * lane.speed * seconds
          lane.swap += lane.speed * seconds
          // 这一列是否已经亮起（跨过它自己的阈值）
          if (strength <= lane.appearAt) continue
          if (lane.head < -lane.cells * lane.lineHeight - 300) {
            lane.head = height + 200
            lane.glyphs = lane.glyphs.map(() => CODE_GLYPHS[Math.floor(Math.random() * CODE_GLYPHS.length)])
          } else if (lane.head > height + 300) {
            lane.head = -lane.cells * lane.lineHeight - 200
            lane.glyphs = lane.glyphs.map(() => CODE_GLYPHS[Math.floor(Math.random() * CODE_GLYPHS.length)])
          }
          if (lane.swap > 22) {
            lane.swap = 0
            const at = Math.floor(Math.random() * lane.cells)
            lane.glyphs[at] = CODE_GLYPHS[Math.floor(Math.random() * CODE_GLYPHS.length)]
          }
          /** 亮度 = 整体强度 × 刚亮起时的快速淡入。 */
          const edge = Math.min(strength, Math.min(1, (strength - lane.appearAt) / 0.22))
          if (edge <= 0.02) continue
          context.font = `600 ${lane.size.toFixed(0)}px "SF Mono", ui-monospace, Consolas, monospace`
          for (let cell = 0; cell < lane.cells; cell += 1) {
            if (glyphPace < 1 && (cell + lane.glyphs.length) % 3 !== 0) continue
            const y = lane.head + (lane.up ? -cell : cell) * lane.lineHeight
            if (y < -40 || y > height + 40) continue
            // 头部最亮、尾部渐隐：像一列驶过的车厢
            const tail = 1 - cell / lane.cells
            const alpha = lane.alpha * (0.28 + tail * 0.72) * edge
            if (alpha <= 0.012) continue
            context.fillStyle = lane.accentLane
              ? `rgba(${accentRgb.r},${accentRgb.g},${accentRgb.b},${alpha.toFixed(3)})`
              : `rgba(14,14,20,${(alpha * 0.94).toFixed(3)})`
            context.fillText(lane.glyphs[cell], lane.x, y)
          }
        }

        frame = window.requestAnimationFrame(draw)
      }

      frame = window.requestAnimationFrame(draw)
      life.add(() => {
        stopped = true
        window.cancelAnimationFrame(frame)
        window.removeEventListener('resize', resize)
      })
    }

    return { node: canvas, mounted: start, destroy: () => life.dispose() }
  }

  // ─────────────────────────── 场景 ───────────────────────────

  /** CSS 兜底风景：随包图片不可用时仍然给出落地窗与城市天际线。 */
  function FallbackScene() {
    return h('div', { className: 'dshsplash-fallback', 'aria-hidden': 'true' },
      h('div', { className: 'sky' }),
      h('div', { className: 'city' }),
      h('div', { className: 'frame' }),
    )
  }

  /**
   * 面向落地窗的场景：背景图（或兜底风景）+ 室内柔光 + 玻璃雨。
   * [DSH Mobile] The background is the user's picture (`picture.img`) instead of the original's window-scene.jpg.
   * Without a picture, or if it fails to load, the original's own CSS fallback scene is shown.
   */
  function Scene(props) {
    const sceneImage = props.picture ? PICTURE_PATH : null
    const sceneEl = h('div', { className: 'dshsplash-scene' })
    const rain = Rain({ density: props.rainDensity, compact: props.compact })
    const interior = h('div', { className: 'dshsplash-interior' })
    const camera = h('div', { className: 'dshsplash-camera' },
      sceneEl,
      interior,
      h('div', { className: 'dshsplash-shaft' }),
      rain,
      h('div', { className: 'dshsplash-glass-sheen' }),
    )
    let fallback = null
    const useImage = (yes) => {
      if (yes) {
        sceneEl.style.backgroundImage = `url("${sceneImage}")`
        if (fallback !== null) {
          fallback.remove()
          fallback = null
        }
      } else {
        sceneEl.style.backgroundImage = ''
        if (fallback === null) {
          fallback = FallbackScene()
          camera.insertBefore(fallback, interior)
        }
      }
    }
    // While the picture loads the room stays plain, as the original's did while its image decoded.
    if (sceneImage === null) useImage(false)

    let cancelled = false
    const start = () => {
      mounted(rain)
      if (sceneImage === null) return
      pictureReady().then((ok) => { if (!cancelled) useImage(ok) })
    }
    return {
      node: h('div', { className: 'dshsplash-room', 'data-period': props.period, 'aria-hidden': 'true' }, camera),
      mounted: start,
      destroy: () => {
        cancelled = true
        if (rain) rain.destroy()
      },
    }
  }

  // ─────────────────────────── [DSH Mobile] 照片（replaces the desktop pet） ───────────────────────────

  /**
   * The original looped a dsh-pet animation here, fetched from the dsh-pet plugin's HTTP route, and showed nothing
   * when that failed. Here the user's picture takes that place, in the pet's container with the pet's shadow, drop
   * shadow and bob. Like the original, the block only appears once the image has loaded, and never on an error.
   * [DSH Mobile] It shows the face square avatar.img; if that fails, picture.img; if both fail, nothing (see
   * avatarSource).
   */
  function PictureCompanion(props) {
    if (!props.picture) return null
    const image = h('img', { className: 'dshsplash-pet-video dshm-pet-photo', alt: '', draggable: 'false' })
    const node = h('div', {
      className: 'dshsplash-pet',
      'data-compact': String(Boolean(props.compact)),
      'aria-hidden': 'true',
      style: { display: 'none' },
    },
    h('span', { className: 'dshsplash-pet-shadow' }),
    image,
    )
    let cancelled = false
    image.onload = () => { if (!cancelled) node.style.display = '' }
    image.onerror = () => { if (!cancelled) node.remove() }
    return {
      node,
      mounted: () => {
        avatarSource().then((src) => {
          if (cancelled) return
          if (src !== null) image.src = src
          else node.remove()
        })
      },
      destroy: () => { cancelled = true },
    }
  }

  // ─────────────────────────── 数码海浪 ───────────────────────────

  /**
   * 数码海浪：一整条横线铺满屏幕宽度，沿垂直方向扫过；浪头粉色，尾迹由粉变白。
   * @param props.duration - 整段持续时长（毫秒），最后 20% 整体淡出。
   * @param props.intensity - 密度倍率（0.2..2）。
   */
  function Waves(props) {
    const canvas = h('canvas', { className: 'dshsplash-waves', 'aria-hidden': 'true' })
    const duration = Math.max(800, Number(props.duration) || 6000)
    const intensity = Math.min(2, Math.max(0.2, Number(props.intensity) || 1))
    const accent = normalizeAccentColor(props.accent)
    const life = scope()

    const start = () => {
      const context = canvas.getContext('2d')
      if (context === null) return
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      let width = 0
      let height = 0
      const resize = () => {
        width = window.innerWidth
        height = window.innerHeight
        canvas.width = Math.max(1, Math.floor(width * dpr))
        canvas.height = Math.max(1, Math.floor(height * dpr))
        canvas.style.width = `${width}px`
        canvas.style.height = `${height}px`
      }
      resize()
      window.addEventListener('resize', resize)

      /** 像素方块边长：越大越"块"，海浪整体也越醒目。 */
      const CELL = 11
      const waves = []
      let nextSpawn = 180
      let frame = 0
      let startedAt = 0
      let last = 0
      let stopped = false

      /** 涌出一条海浪（dir 1 = 从上往下扫，-1 = 从下往上扫）。 */
      const spawn = () => {
        const dir = Math.random() < 0.5 ? 1 : -1
        const fromLeft = Math.random() < 0.5
        waves.push({
          dir,
          head: dir > 0 ? -height * 0.28 : height * 1.28,
          speed: (height * (0.4 + Math.random() * 0.3)) / 1,
          waveLength: 340 + Math.random() * 520,
          amp: 22 + Math.random() * 34,
          phase: Math.random() * Math.PI * 2,
          phaseSpeed: 0.9 + Math.random() * 1.2,
          trail: 240 + Math.random() * 340,
          alpha: 0.68 + Math.random() * 0.3,
          skew: (fromLeft ? 1 : -1) * (0.006 + Math.random() * 0.016),
          crestRows: 3 + Math.floor(Math.random() * 3),
        })
      }

      const draw = (timestamp) => {
        if (stopped) return
        if (startedAt === 0) startedAt = timestamp
        const elapsed = timestamp - startedAt
        const delta = last === 0 ? 16 : Math.min(48, timestamp - last)
        last = timestamp
        const seconds = delta / 1000
        context.setTransform(dpr, 0, 0, dpr, 0, 0)
        context.clearRect(0, 0, width, height)

        // 随机涌出：同时最多 3 条
        nextSpawn -= delta
        if (nextSpawn <= 0 && waves.length < 3) {
          spawn()
          nextSpawn = (1100 + Math.random() * 1500) / intensity
        }

        const tailFade = elapsed > duration * 0.8
          ? Math.max(0, 1 - (elapsed - duration * 0.8) / (duration * 0.2))
          : 1

        for (let index = waves.length - 1; index >= 0; index -= 1) {
          const wave = waves[index]
          wave.head += wave.dir * wave.speed * seconds
          wave.phase += seconds * wave.phaseSpeed
          // 越过另一侧就回收
          if (wave.head < -height * 0.6 - wave.trail || wave.head > height * 1.6 + wave.trail) {
            waves.splice(index, 1)
            continue
          }

          // 整条线的基准 y（含沿线的轻微倾斜）
          const baseAt = (x) => wave.head + (x - width / 2) * wave.skew

          // 沿水平方向铺满整屏，步长 = 方块边长
          for (let x = 0; x <= width; x += CELL) {
            const base = baseAt(x)
            const onWave = Math.sin((x / wave.waveLength) * Math.PI * 2 + wave.phase) * wave.amp
            const steps = Math.ceil(wave.trail / CELL)
            for (let step = 0; step <= steps; step += 1) {
              const dist = step * CELL
              const age = dist / wave.trail
              const y = base + onWave - wave.dir * dist
              if (y < -CELL || y > height + CELL) continue
              // 颜色：浪头是主色，沿尾迹插值到白
              const whiten = Math.min(1, age * 1.25)
              const [r, g, b] = accentToWhite(accent, whiten)
              const alpha = wave.alpha * Math.pow(1 - age, 1.6) * tailFade
              if (alpha <= 0.02) continue
              context.fillStyle = `rgba(${r},${g},${b},${alpha.toFixed(3)})`
              // 像素方块：留一道细缝；浪头略厚
              const rows = age < 0.06 ? wave.crestRows : 1
              for (let row = 0; row < rows; row += 1) {
                context.fillRect(x, y + row * CELL, CELL - 1.4, CELL - 1.4)
              }
            }
          }
        }

        frame = window.requestAnimationFrame(draw)
      }

      frame = window.requestAnimationFrame(draw)
      life.add(() => {
        stopped = true
        window.cancelAnimationFrame(frame)
        window.removeEventListener('resize', resize)
      })
    }

    return { node: canvas, mounted: start, destroy: () => life.dispose() }
  }

  // ─────────────────────────── 线条生长 ───────────────────────────

  /** 一条「生长出来」的线：赛博感的基础装饰元素。 */
  function LineGrow(props) {
    const style = {
      '--dshs-grow-ms': `${Math.max(120, Number(props.duration) || 900)}ms`,
      '--dshs-grow-delay': `${Math.max(0, Number(props.delay) || 0)}ms`,
      ...(props.style ?? {}),
    }
    return h('span', {
      className: props.vertical === true ? 'dshsplash-grow-v' : 'dshsplash-grow',
      style,
      'aria-hidden': 'true',
    })
  }

  // ─────────────────────────── 启动终端（一个界面走完三步） ───────────────────────────

  /**
   * 终端第一行自动输入的内容。
   * [DSH Mobile] The original typed `running Deepseek Harness`; the app's own name takes its place.
   */
  const BOOT_COMMAND = 'running DSH Mobile'

  /** 预载清单：把开屏动画"当成一次真实的工作区启动"来演示。（unchanged） */
  const WORKLOAD_PLUGINS = [
    { name: 'dsh-base', base: 1.0, note: 'core' },
    { name: 'dsh-web-app', base: 1.15, note: 'surface' },
    { name: 'dsh-agent-preset', base: 0.9, note: 'agents' },
    { name: 'dsh-session-controller', base: 1.05, note: 'session' },
    { name: 'dsh-tool-surface', base: 0.95, note: 'tools' },
    { name: 'dsh-context-doctor', base: 0.85, note: 'context' },
    { name: 'dsh-better-sidebar', base: 0.8, note: 'ui' },
    { name: 'dsh-plugin-wallpaper-engine', base: 1.2, note: 'render' },
    { name: 'dsh-pet', base: 0.75, note: 'assets' },
    { name: 'dsh-ego-browser', base: 1.1, note: 'browser' },
    { name: 'dsh-plugin-save-token', base: 0.7, note: 'tokens' },
    { name: 'dsh-neon-splash-v3', base: 1.25, note: 'splash' },
  ]

  /** 每行右侧的参数可视化：5 根高度随进度起伏的等宽条。 */
  function MiniMeter(seed) {
    const bars = new Array(5).fill(null).map(() => h('i', null))
    const node = h('span', { className: 'dshsplash-console-meter', 'aria-hidden': 'true' }, bars)
    const update = (value) => {
      const progress = Math.min(1, Math.max(0, Number(value) || 0))
      bars.forEach((bar, index) => {
        // 伪随机但确定：同一个 (seed, index) 每次渲染得到同一高度
        const wave = Math.abs(Math.sin((seed + 1) * 1.7 + index * 2.3 + progress * 3.1))
        const level = 2 + Math.round(wave * 10 * (0.35 + progress * 0.65))
        bar.style.height = `${level}px`
      })
    }
    return { node, update }
  }

  /** 预载清单里的一行：名字 + 数码分段进度条 + 百分比 + 参数条。 */
  function ConsoleRow(name, seed) {
    const fill = h('i', null)
    const pct = h('span', { className: 'dshsplash-console-pct' })
    const meter = MiniMeter(seed)
    const node = h('div', { className: 'dshsplash-console-row', 'data-done': 'false' },
      h('span', { className: 'dshsplash-console-name' }, name),
      h('span', { className: 'dshsplash-console-bar' }, fill),
      pct,
      meter,
    )
    const update = (value) => {
      const percent = Math.floor(Math.min(1, Math.max(0, Number(value) || 0)) * 100)
      node.setAttribute('data-done', String(percent >= 100))
      fill.style.width = `${percent}%`
      pct.textContent = `${String(percent).padStart(3, ' ')}%`
      meter.update(percent / 100)
    }
    return { node, update }
  }

  /**
   * 启动终端：**一个界面走完三步**（命令行启动 → 连接工作区模型 → 工作区预载）。
   * 进度由**单个 requestAnimationFrame 循环**推进（一个进度源）。
   */
  function BootSequence(props) {
    let stage = Number(props.stage) || 0
    const bootMs = Math.max(300, Number(props.bootMs) || 2000)
    const connectMs = Math.max(300, Number(props.connectMs) || 1200)
    const loadingMs = Math.max(800, Number(props.loadingMs) || 7000)
    const model = typeof props.model === 'string' && props.model !== '' ? props.model : FALLBACK.model
    let typed = ''
    let ratio = 0
    let typingLife = null
    let ratioLife = null

    /** 预载各行的起止时间窗：按权重分配，并留一点重叠，看起来像并行加载。 */
    const windows = (() => {
      const total = WORKLOAD_PLUGINS.reduce((sum, item) => sum + item.base, 0)
      const usable = 0.88 // 最后 12% 留给"全部 ready"
      let cursor = 0
      return WORKLOAD_PLUGINS.map((item) => {
        const span = (item.base / total) * usable
        const window = { start: cursor, end: Math.min(1, cursor + span * 1.6) }
        cursor += span
        return window
      })
    })()

    const headPct = h('b', null)
    const cmd = h('span', { className: 'cmd' })
    const caret = h('span', { className: 'dshsplash-boot-caret' })
    const bootLine = h('div', { className: 'dshsplash-boot-line' }, h('span', { className: 'prompt' }, '> '), cmd)
    const midGrow = LineGrow({ delay: 140, duration: 900, style: { width: '100%', margin: '2px 0 12px', opacity: '.7' } })
    const footPlugins = h('b', null)
    const footModel = h('b', null)
    const footMount = h('b', null)
    const node = h('div', { className: 'dshsplash-console', 'data-role': 'boot-sequence', 'data-stage': String(stage) },
      h('div', { className: 'dshsplash-console-head' },
        h('span', { className: 'dshsplash-boot-dot' }),
        h('span', null, 'dsh · startup'),
        LineGrow({ delay: 60, duration: 700, style: { flex: '1 1 auto', margin: '0 12px' } }),
        headPct,
      ),
      bootLine,
      midGrow,
      h('div', { className: 'dshsplash-console-foot' },
        h('span', null, 'plugins'), footPlugins,
        h('span', null, 'model'), footModel,
        h('span', null, 'mount'), footMount,
      ),
    )
    let connect = null
    let rows = null

    const render = () => {
      node.setAttribute('data-stage', String(stage))
      const typing = stage === STAGE.BOOT && typed.length < BOOT_COMMAND.length
      const connectPercent = stage === STAGE.BOOT ? 0 : Math.round(ratio * 100)
      headPct.textContent = stage === STAGE.LOADING ? `${Math.round(ratio * 100)}%` : 'online'
      cmd.textContent = stage === STAGE.BOOT ? typed : BOOT_COMMAND
      if (typing && caret.parentNode !== bootLine) bootLine.appendChild(caret)
      if (!typing && caret.parentNode === bootLine) caret.remove()

      // ── 第二行：正在连接工作区模型（带进度条） ──
      if (stage >= STAGE.CONNECT && connect === null) {
        const fill = h('i', null)
        const pct = h('span', { className: 'dshsplash-console-pct' })
        connect = {
          fill,
          pct,
          node: h('div', { className: 'dshsplash-console-connect' },
            h('span', { className: 'prompt' }, '> '),
            h('span', { className: 'cmd' }, 'connecting workspace model '),
            h('b', null, model),
            h('span', { className: 'dshsplash-console-bar dshsplash-console-bar-inline' }, fill),
            pct,
          ),
        }
        node.insertBefore(connect.node, midGrow)
      }
      if (connect !== null) {
        connect.fill.style.width = `${connectPercent}%`
        connect.pct.textContent = stage === STAGE.CONNECT ? `${String(connectPercent).padStart(3, ' ')}%` : ' ok'
      }

      // ── 第三段：工作区预载，各插件 0 → 100 ──
      if (stage >= STAGE.LOADING && rows === null) {
        rows = WORKLOAD_PLUGINS.map((item, index) => ConsoleRow(item.name, index))
        node.insertBefore(h('div', { className: 'dshsplash-console-rows' }, rows), midGrow)
      }
      if (rows !== null && stage === STAGE.LOADING) {
        rows.forEach((row, index) => {
          const window = windows[index]
          row.update(Math.min(1, Math.max(0, (ratio - window.start) / (window.end - window.start))))
        })
      }

      footPlugins.textContent = stage === STAGE.LOADING
        ? `${Math.round(ratio * 100)}%`
        : `${WORKLOAD_PLUGINS.length}/${WORKLOAD_PLUGINS.length}`
      footModel.textContent = stage === STAGE.CONNECT ? 'linking' : 'linked'
      footMount.textContent = stage === STAGE.LOADING && ratio >= 1 ? 'ready' : 'running'
    }

    /** The two effects of the original, re-run when the stage changes. */
    const effects = () => {
      if (typingLife !== null) typingLife.dispose()
      typingLife = null
      if (stage === STAGE.BOOT) {
        typingLife = scope()
        const typeMs = Math.max(260, Math.round(bootMs * 0.78))
        const step = Math.max(14, Math.round(typeMs / BOOT_COMMAND.length))
        let index = 0
        const timer = typingLife.interval(() => {
          index += 1
          typed = BOOT_COMMAND.slice(0, index)
          render()
          if (index >= BOOT_COMMAND.length) window.clearInterval(timer)
        }, step)
      }

      /** 连接模型 + 预载共用一个进度源：connect 阶段跑 0→1，loading 阶段重置重跑。 */
      if (ratioLife !== null) ratioLife.dispose()
      ratioLife = null
      if (stage === STAGE.CONNECT || stage === STAGE.LOADING) {
        ratioLife = scope()
        const duration = stage === STAGE.CONNECT ? connectMs : loadingMs
        const startedAt = performance.now()
        let frame = 0
        let stopped = false
        const tick = (now) => {
          if (stopped) return
          ratio = Math.min(1, (now - startedAt) / duration)
          render()
          if (ratio < 1) frame = window.requestAnimationFrame(tick)
        }
        ratio = 0
        render()
        frame = window.requestAnimationFrame(tick)
        ratioLife.add(() => {
          stopped = true
          window.cancelAnimationFrame(frame)
        })
      }
    }

    render()
    return {
      node,
      mounted: effects,
      setStage(next) {
        if (next === stage) return
        stage = next
        render()
        effects()
      },
      destroy() {
        if (typingLife !== null) typingLife.dispose()
        if (ratioLife !== null) ratioLife.dispose()
      },
    }
  }

  // ─────────────────────────── 数码波纹 ───────────────────────────

  /**
   * 数码波纹：像石头丢进水面那样，从落点扩散出一圈圈由像素方块构成的波。
   * 完成回调**必须只触发一次、且一定会触发**。
   */
  function Ripple(props) {
    const canvas = h('canvas', { className: 'dshsplash-ripple', 'aria-hidden': 'true' })
    const duration = Math.max(300, Number(props.duration) || 1200)
    const accent = normalizeAccentColor(props.accent)
    const life = scope()

    const start = () => {
      let done = false
      const finish = () => {
        if (done) return
        done = true
        if (typeof props.onDone === 'function') props.onDone()
      }
      const context = canvas.getContext('2d')
      if (context === null) {
        // 取不到 2D 上下文：跳过绘制，但**必须**报告完成
        finish()
        return
      }
      /** 独立的兜底定时器：绝不把流程卡在登录界面。 */
      life.timeout(finish, duration + 240)
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      let width = 0
      let height = 0
      const resize = () => {
        width = window.innerWidth
        height = window.innerHeight
        canvas.width = Math.max(1, Math.floor(width * dpr))
        canvas.height = Math.max(1, Math.floor(height * dpr))
        canvas.style.width = `${width}px`
        canvas.style.height = `${height}px`
      }
      resize()
      window.addEventListener('resize', resize)

      /** 方块边长：小一点才有"数码"的颗粒感。 */
      const CELL = 9
      /** 一圈圈往外扩散的波环：起始延迟比例。 */
      const RINGS = [0, 0.16, 0.32]
      const MAX_R = Math.hypot(width, height) * 0.42
      const startedAt = performance.now()
      let frame = 0
      let stopped = false

      const draw = (now) => {
        if (stopped) return
        const elapsed = now - startedAt
        const progress = Math.min(1, elapsed / duration)
        context.setTransform(dpr, 0, 0, dpr, 0, 0)
        context.clearRect(0, 0, width, height)

        const cx = Number(props.x) || width / 2
        const cy = Number(props.y) || height / 2

        for (const delayRatio of RINGS) {
          const ring = (progress - delayRatio) / (1 - delayRatio)
          if (ring < 0 || ring > 1) continue
          const radius = MAX_R * ring
          if (radius < CELL) continue
          const life2 = 1 - ring
          const alpha = 0.85 * life2 * life2
          if (alpha <= 0.02) continue
          const steps = Math.max(12, Math.floor((Math.PI * 2 * radius) / (CELL * 1.25)))
          for (let index = 0; index < steps; index += 1) {
            const angle = (index / steps) * Math.PI * 2
            const x = cx + Math.cos(angle) * radius
            const y = cy + Math.sin(angle) * radius * 0.96
            if (x < -CELL || x > width + CELL || y < -CELL || y > height + CELL) continue
            const whiten = Math.min(1, 0.25 + ring * 1.35)
            const [r, g, b] = accentToWhite(accent, whiten)
            const scale = index % 2 === 0 ? 1 : 0.72
            context.fillStyle = `rgba(${r},${g},${b},${(alpha * (index % 2 === 0 ? 1 : 0.6)).toFixed(3)})`
            const size = CELL * scale
            context.fillRect(x - size / 2, y - size / 2, size, size)
          }
        }

        if (progress >= 1) {
          stopped = true
          finish()
          return
        }
        frame = window.requestAnimationFrame(draw)
      }

      frame = window.requestAnimationFrame(draw)
      life.add(() => {
        stopped = true
        window.cancelAnimationFrame(frame)
        window.removeEventListener('resize', resize)
      })
    }

    return { node: canvas, mounted: start, destroy: () => life.dispose() }
  }

  // ─────────────────────────── 登录 ───────────────────────────

  /** 密码位数（密码用 * 显示）。 */
  const LOGIN_PASSWORD_LENGTH = 8
  /** 数码波纹的单次时长（毫秒）。 */
  const LOGIN_RIPPLE_MS = 1400
  /** 输完密码后等待用户点击的上限；超时自动继续，避免没人操作时卡住。 */
  const LOGIN_AUTO_CLICK_MS = 6000
  /** [DSH Mobile] named here; the original wrote 420 inline (ACCESS GRANTED → continue). */
  const LOGIN_GRANTED_MS = 420
  /** [DSH Mobile] the original's fallback username was the author's handle. */
  const LOGIN_USER_FALLBACK = '訪客'

  /**
   * 登录动画：中轴对称，上方（原作是动态桌宠，[DSH Mobile] 这里是使用者的照片），下方是数码风格登录框。
   * 用户名与密码**逐字打出**，输完后按钮变亮等待**用户点击**；点击时从按钮落点砸开一圈数码方块波纹，
   * 再走握手流程并通知时间线继续。若一直没人点，`LOGIN_AUTO_CLICK_MS` 后自动继续。
   *
   * [DSH Mobile] `props.gate` scales the original's fixed waits (auto-continue, ripple, the 420 ms after ACCESS
   * GRANTED) when the app asks for a shorter run with `seconds`; it is 1 otherwise.
   */
  function Login(props) {
    const duration = Math.max(600, Number(props.duration) || 2600)
    const gate = Number(props.gate) || 1
    const userChars = graphemes(props.username !== '' ? props.username : LOGIN_USER_FALLBACK)
    const userName = userChars.join('')
    const total = userChars.length + LOGIN_PASSWORD_LENGTH
    const life = scope()
    let phaseLife = scope()
    let typedCount = 0
    let phase = 'typing'
    let submitted = false
    let ripple = null

    // ── DOM ──
    const userText = document.createTextNode('')
    const userCaret = h('span', { className: 'dshsplash-login-caret' }, '\u00A0')
    const userValue = h('div', { className: 'dshsplash-login-value' }, userText)
    const passText = document.createTextNode('')
    const passCaret = h('span', { className: 'dshsplash-login-caret' }, '\u00A0')
    const passValue = h('div', { className: 'dshsplash-login-value' }, passText)
    const dots = new Array(LOGIN_PASSWORD_LENGTH).fill(null).map(() => h('i', { 'data-on': 'false' }))
    const button = h('button', { type: 'button', className: 'dshsplash-login-submit', 'data-ready': 'false', 'data-role': 'login-submit' })
    const status = h('div', { className: 'dshsplash-login-status' })
    const picture = PictureCompanion({ picture: props.picture, compact: false })
    const node = h('div', { className: 'dshsplash-login', 'data-role': 'login', 'data-phase': phase },
      h('div', { className: 'dshsplash-login-axis' }),
      h('div', { className: 'dshsplash-login-pet' }, picture),
      h('div', { className: 'dshsplash-login-card' },
        h('div', { className: 'dshsplash-login-title' }, 'SYSTEM ACCESS'),
        h('div', { className: 'dshsplash-login-field' },
          h('div', { className: 'dshsplash-login-label' }, 'USERNAME'),
          userValue,
        ),
        h('div', { className: 'dshsplash-login-field dshsplash-login-password' },
          h('div', { className: 'dshsplash-login-label' }, 'PASSWORD'),
          passValue,
        ),
        h('div', { className: 'dshsplash-login-dots' }, dots),
        button,
        status,
      ),
    )

    const toggle = (parent, child, show) => {
      if (show && child.parentNode !== parent) parent.appendChild(child)
      if (!show && child.parentNode === parent) child.remove()
    }

    const render = () => {
      const userCount = Math.min(typedCount, userChars.length)
      const passCount = Math.max(0, Math.min(typedCount - userChars.length, LOGIN_PASSWORD_LENGTH))
      const userDone = userCount >= userChars.length
      const passwordDone = passCount >= LOGIN_PASSWORD_LENGTH
      const ready = userDone && passwordDone
      node.setAttribute('data-phase', phase)
      userText.data = userChars.slice(0, userCount).join('')
      toggle(userValue, userCaret, !userDone)
      passText.data = '*'.repeat(passCount)
      toggle(passValue, passCaret, userDone && !passwordDone)
      dots.forEach((dot, index) => dot.setAttribute('data-on', String(index < passCount)))
      button.setAttribute('data-ready', String(ready))
      button.disabled = !ready || phase === 'auth' || phase === 'granted'
      button.textContent = phase === 'typing' || phase === 'ready'
        ? 'SIGN IN'
        : phase === 'auth' ? 'VERIFYING' : 'ACCESS GRANTED'
      // [DSH Mobile] status lines in Traditional Chinese; the greeting is the user's welcome words, where the
      // original played its "welcome back, master" voice clip and printed WELCOME + the username.
      status.textContent = phase === 'typing'
        ? `SECURE INPUT · ${typedCount}/${total}`
        : phase === 'ready'
          ? '點一下 SIGN IN 繼續'
          : phase === 'auth'
            ? 'HANDSHAKE .......... OK'
            : `IDENTITY CONFIRMED · ${props.welcome} ${userName}`
    }

    const center = () => {
      const box = button.getBoundingClientRect()
      return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
    }

    /** 提交：砸波纹 → 进入握手 → 通知时间线。 */
    const submit = (point) => {
      if (submitted) return
      submitted = true
      setPhase('auth')
      ripple = Ripple({
        x: point.x,
        y: point.y,
        duration: LOGIN_RIPPLE_MS * gate,
        accent: props.accent,
        onDone: () => setPhase('granted'),
      })
      node.appendChild(ripple.node)
      mounted(ripple)
    }

    /** The original's three [phase] effects; each phase change clears the previous phase's timer. */
    function setPhase(next) {
      if (next === phase) return
      phase = next
      phaseLife.dispose()
      phaseLife = scope()
      render()
      if (phase === 'ready') {
        // 输完之后等用户点击；超时则自动继续，避免没人操作时卡住
        phaseLife.timeout(() => submit(center()), LOGIN_AUTO_CLICK_MS * gate)
      } else if (phase === 'granted') {
        // 波纹播完 → 进入 ACCESS GRANTED → 通知时间线继续
        phaseLife.timeout(() => props.onSubmit(), LOGIN_GRANTED_MS * gate)
      } else if (phase === 'auth') {
        // 双保险：只要提交过，最多 3 秒后一定通知时间线继续
        phaseLife.timeout(() => props.onSubmit(), (LOGIN_RIPPLE_MS + 1600) * gate)
      }
    }

    life.on(button, 'click', () => {
      if (button.getAttribute('data-ready') !== 'true') return
      submit(center())
    })

    const start = () => {
      mounted(picture)
      const typeMs = Math.max(300, Math.round(duration * 0.5))
      const step = Math.max(20, Math.round(typeMs / total))
      const timer = life.interval(() => {
        typedCount += 1
        render()
        if (typedCount >= total) {
          window.clearInterval(timer)
          // 只在还是 typing 时才改成 ready（用户可能已经点了）
          if (phase === 'typing') setPhase('ready')
        }
      }, step)
    }

    render()
    return {
      node,
      mounted: start,
      destroy() {
        life.dispose()
        phaseLife.dispose()
        if (ripple !== null) ripple.destroy()
        if (picture !== null) picture.destroy()
      },
    }
  }

  // ─────────────────────────── AR HUD ───────────────────────────

  /** 开机日志：iOS 玻璃面板里逐行打出的连续文本。（unchanged） */
  const BOOT_LINES = [
    '> COMPILE  ....... CODE → HUD',
    '> OPTICS  ....... ONLINE',
    '> QUANTUM LINK ... ESTABLISHED',
    '> BIOMETRIC ..... VERIFIED',
    '> NEURAL SYNC ... 98.2%',
    '> SYSTEM ........ READY',
  ]

  /**
   * [DSH Mobile] The HUD title was `Deepseek Harness`; it is the app's name. The large pink byline was
   * `designed by Z1ro2`; it is the user's welcome words and name (the author is credited in NOTICE.md and LICENSE).
   */
  const HUD_TITLE = 'DSH Mobile'

  /**
   * AR HUD：由屏幕中心向四周展开，展开结束后文字内容才入场。
   * [DSH Mobile] `props.still` builds the finished frame at once for the reduced-motion version.
   */
  function Hud(props) {
    const textMs = Math.max(300, Number(props.textMs) || 1800)
    const life = scope()
    const full = BOOT_LINES.join('\n')
    const logText = document.createTextNode(props.still ? full : '')
    const readout = h('div', null, 'SYNC 00.0%')
    const ios = h('div', { className: 'dshsplash-ios', 'data-visible': String(Boolean(props.still)) })
    const panel = h('div', { className: 'dshsplash-ios-panel', 'data-visible': String(Boolean(props.still)) },
      h('pre', { className: 'dshsplash-ios-log' }, logText, h('span', { className: 'caret' }, '\u00A0')),
    )
    const picture = PictureCompanion({ picture: props.picture, compact: false })
    const byline = h('div', { className: 'dshsplash-ios-byline' },
      props.name !== '' ? h('em', null, props.welcome) : null,
      h('b', null, props.name !== '' ? props.name : props.welcome),
    )
    append(ios, [
      h('h1', { className: 'dshsplash-ios-title' }, HUD_TITLE),
      h('div', { className: 'dshsplash-ios-rule' }),
      picture,
      LineGrow({ delay: 120, duration: 800, style: { width: 'min(22vw, 150px)' } }),
      byline,
      LineGrow({ delay: 120, duration: 800, style: { width: 'min(22vw, 150px)' } }),
      h('div', { className: 'dshsplash-sig' }, 'COMPILED · AR · ONLINE'),
    ])
    const item = (className, i, ...children) => h('div', { className: `${className} dshsplash-hud-item`, style: { '--dshs-i': i } }, ...children)
    const node = h('div', {
      className: 'dshsplash-hud',
      'data-period': props.period,
      'data-compact': 'false',
      'data-revealed': String(Boolean(props.still)),
      'data-role': 'ar-hud',
    },
    h('div', { className: 'dshsplash-hud-panel' }),
    h('div', { className: 'dshsplash-hud-iris' }),
    h('div', { className: 'dshsplash-hud-core-dot' }),
    h('div', { className: 'dshsplash-hudlines' }),
    item('dshsplash-grid', 0.24),
    item('dshsplash-scan', 0.3),
    item('dshsplash-corner tl', 0.34),
    item('dshsplash-corner tr', 0.38),
    item('dshsplash-corner bl', 0.42),
    item('dshsplash-corner br', 0.46),
    item('dshsplash-reticle', 0.52),
    item('dshsplash-progress', 0.3, h('i', null)),
    item('dshsplash-readout', 0.62,
      h('div', null, 'LAT 3.7ms'),
      h('div', null, 'FOV 118\u00B0'),
      readout,
    ),
    ios,
    panel,
    )
    if (props.still) readout.textContent = 'SYNC 98.2%'

    const start = () => {
      mounted(picture)
      if (props.still) return
      life.timeout(() => {
        node.setAttribute('data-revealed', 'true')
        ios.setAttribute('data-visible', 'true')
        panel.setAttribute('data-visible', 'true')
        const step = Math.max(10, Math.round(textMs / Math.max(1, full.length)))
        let index = 0
        const timer = life.interval(() => {
          index += 1
          logText.data = full.slice(0, index)
          if (index >= full.length) window.clearInterval(timer)
        }, step)
      }, Math.max(0, Number(props.expandMs) || 0))
      life.interval(() => {
        readout.textContent = `SYNC ${(Math.random() * 40 + 60).toFixed(1)}%`
      }, 180)
    }

    return {
      node,
      mounted: start,
      setRevealStyle: (style) => setStyle(node, style),
      destroy() {
        life.dispose()
        if (picture !== null) picture.destroy()
      },
    }
  }

  // ─────────────────────────── 开屏组件 ───────────────────────────

  /** 阶段编号。 */
  const STAGE = {
    BLACK: 0,
    OPEN: 1,
    BOOT: 2,
    CONNECT: 3,
    GLASSES: 4,
    LOADING: 5,
    LOGIN: 6,
    AUTH: 6,
    CODING: 7,
    HUD: 8,
    TEXT: 9,
    PARTICLES: 10,
    FADE: 11,
  }
  /** 每个时刻对应的阶段号。 */
  const STAGE_OF = {
    open: STAGE.OPEN,
    boot: STAGE.BOOT,
    connect: STAGE.CONNECT,
    glasses: STAGE.GLASSES,
    loading: STAGE.LOADING,
    login: STAGE.LOGIN,
    loginwait: STAGE.AUTH,
    braindance: STAGE.CODING,
    hud: STAGE.HUD,
    text: STAGE.TEXT,
    particles: STAGE.PARTICLES,
    fade: STAGE.FADE,
  }
  /** 终端界面的三个连续阶段（boot / connect / loading 共用同一个面板）。 */
  const TERMINAL_STAGES = [STAGE.BOOT, STAGE.CONNECT, STAGE.LOADING]

  /**
   * [DSH Mobile] How long a run takes at a given speed when nobody taps SIGN IN. The timeline's moments are
   * absolute (counted from the start), so the login gate does not push the end back: a late resume only shortens
   * the code → HUD stage, as in the original. The run ends at `done`, or at the resume if that comes even later.
   */
  function predictRun(config, gate, chars) {
    const plan = buildTimeline(config, SCENE_LAUNCH)
    const at = (kind) => plan.timeline.find((moment) => moment.kind === kind).at
    const loginAt = at('login')
    const bdAt = at('braindance')
    const endAt = at('done')
    const loginMs = Math.max(600, plan.durations.login)
    const typeMs = Math.max(300, Math.round(loginMs * 0.5))
    const typedAt = loginAt + Math.max(20, Math.round(typeMs / chars)) * chars
    const byLogin = typedAt + LOGIN_AUTO_CLICK_MS * gate + Math.max(300, LOGIN_RIPPLE_MS * gate) + LOGIN_GRANTED_MS * gate
    const byGate = bdAt + (LOGIN_AUTO_CLICK_MS + LOGIN_RIPPLE_MS + 2200) * gate
    return Math.max(endAt, Math.min(byLogin, byGate))
  }

  /**
   * [DSH Mobile] `seconds` uses the original's own speed setting (`timeScale`, 0.05–4): the whole story is played
   * faster or slower so a run lasts about `seconds`. Without `seconds` it plays at the original pace (about 42 s).
   */
  function solveTimeScale(seconds, chars) {
    if (seconds === null) return 1
    const target = seconds * 1000
    const run = (scale) => predictRun({ ...FALLBACK, timeScale: scale }, scale, chars)
    let low = 0.05
    let high = 4
    if (run(low) >= target) return low
    if (run(high) <= target) return high
    for (let round = 0; round < 40; round += 1) {
      const middle = (low + high) / 2
      if (run(middle) < target) low = middle
      else high = middle
    }
    return (low + high) / 2
  }

  /**
   * 开屏动画本体：按时间线推进阶段。
   * [DSH Mobile] The React component and the controller singleton become this function. The stage tree, the
   * timeline machine, the login gate, the skip rules and the opening driver are the original's.
   */
  function playSplash(P) {
    const userChars = graphemes(P.name !== '' ? P.name : LOGIN_USER_FALLBACK)
    const timeScale = solveTimeScale(P.seconds, userChars.length + LOGIN_PASSWORD_LENGTH)
    const gate = P.seconds === null ? 1 : timeScale
    const config = { ...FALLBACK, timeScale, allowSkip: P.skippable }
    const plan = buildTimeline(config, SCENE_LAUNCH)
    const timeline = plan.timeline
    const durations = plan.durations
    const welcome = P.welcome !== '' ? P.welcome : '歡迎回來'
    const startedAt = Date.now()
    const period = resolvePeriod(new Date(), config)
    const revealDelay = durations.rainRise
    const revealMs = durations.rainFall
    const accentVars = buildAccentVars(config.accent, config.accentDeep)

    let step = -1
    let finished = false
    const life = scope()
    let skipLife = scope()
    let openLife = null

    const overlay = h('div', {
      className: 'dshsplash-overlay',
      role: 'presentation',
      'data-scene': SCENE_LAUNCH,
      'data-step': String(step),
      'data-period': period,
      'data-fading': 'false',
    })
    setStyle(overlay, {
      '--dshs-open': `${durations.open}ms`,
      '--dshs-expand': `${durations.hudExpand}ms`,
      '--dshs-text': `${durations.hudText}ms`,
      '--dshs-particles': `${durations.particles}ms`,
      '--dshs-reveal': `${revealMs}ms`,
      ...accentVars,
      animationName: 'none',
      animationDuration: `${revealMs}ms`,
      animationDelay: `${revealDelay}ms`,
      animationTimingFunction: 'cubic-bezier(.4,0,.6,1)',
      animationFillMode: 'forwards',
    })
    const stage = h('div', { className: 'dshsplash-stage' })
    overlay.appendChild(stage)
    const skipHint = config.allowSkip ? h('div', { className: 'dshsplash-skip', 'data-role': 'skip-hint' }) : null
    if (skipHint !== null) overlay.appendChild(skipHint)

    const hudStyle = (revealed) => ({
      animationName: revealed ? 'dshsplash-reveal-out' : 'none',
      animationDuration: `${revealMs}ms`,
      animationDelay: `${revealDelay}ms`,
      animationTimingFunction: 'cubic-bezier(.4,0,.6,1)',
      animationFillMode: 'forwards',
    })

    // ── timeline machine (the original's advance / skipTo) ──
    let index = 0
    let pendingLoginSkip = false
    let atLoginGate = false
    let loginGate = null
    let skipTo = null

    const onSubmit = () => {
      if (loginGate !== null) {
        window.clearTimeout(loginGate)
        loginGate = null
      }
      if (skipTo !== null) skipTo('braindance')
    }

    /** The stage tree, in the original's order. `when` is the original's render condition. */
    const layers = [
      {
        when: (s) => s >= STAGE.OPEN,
        make: () => {
          const scene = Scene({ period, rainDensity: config.rainDensity, compact: durations.compact, picture: P.picture })
          const node = h('div', {
            className: 'dshsplash-room-layer',
            'data-bd': String(step >= STAGE.LOGIN),
            style: { '--dshs-bd-light': String(config.braindanceLight) },
          }, scene)
          return { node, mounted: () => mounted(scene), destroy: () => scene.destroy() }
        },
        update: (part) => part.node.setAttribute('data-bd', String(step >= STAGE.LOGIN)),
      },
      {
        // 电源黑幕：step 0 完全不透明（纯黑开场），开屏收敛段由驱动循环收掉。
        when: () => true,
        make: () => ({ node: h('div', { className: 'dshsplash-screen', 'data-off': String(step >= STAGE.OPEN) }) }),
        update: (part) => part.node.setAttribute('data-off', String(step >= STAGE.OPEN)),
      },
      {
        // 开屏由暗到亮：压在场景之上的黑幕，随"逐渐清晰"一起收掉
        when: (s) => s >= STAGE.OPEN,
        make: () => ({ node: h('div', { className: 'dshsplash-dawn' }) }),
      },
      {
        // 数码海浪：白底界面阶段随机从四角涌出
        when: (s) => s >= STAGE.GLASSES && s < STAGE.FADE,
        make: () => Waves({
          duration: Math.max(2400, durations.login + durations.braindance + durations.hudExpand + durations.hudText + durations.particles),
          intensity: config.waveIntensity,
          accent: config.accent,
        }),
      },
      {
        // 启动终端：命令行启动 → 连接工作区模型 → 工作区预载，三段共用一个面板
        when: (s) => TERMINAL_STAGES.includes(s),
        make: () => BootSequence({
          stage: step,
          bootMs: durations.boot,
          connectMs: durations.connect,
          loadingMs: durations.loading,
          model: config.model,
        }),
        update: (part) => part.setStage(step),
      },
      {
        // 屏幕边缘边框：命令行启动时就浮出，之后一直留在四周
        when: (s) => s >= STAGE.GLASSES,
        make: () => EdgeFrame({ tag: P.number !== '' ? P.number : 'DSH' }),
      },
      {
        // 登录：打完等待用户点 SIGN IN（AUTH 与 LOGIN 是同一界面）
        when: (s) => s === STAGE.LOGIN || s === STAGE.AUTH,
        make: () => Login({
          duration: durations.login,
          gate,
          accent: config.accent,
          username: P.name,
          welcome,
          picture: P.picture,
          onSubmit,
        }),
      },
      {
        // 代码 → HUD：终端先打字，执行时终端收起并炸出粉色冲击环
        when: (s) => s === STAGE.CODING,
        make: () => CodeTerminal({ duration: durations.braindance, subject: userChars.join('') }),
      },
      {
        when: (s) => s >= STAGE.HUD,
        make: () => {
          const hud = Hud({
            period,
            expandMs: durations.hudExpand,
            textMs: durations.hudText,
            picture: P.picture,
            name: P.name,
            welcome,
          })
          hud.setRevealStyle(hudStyle(step >= STAGE.PARTICLES))
          return hud
        },
        update: (part) => part.setRevealStyle(hudStyle(step >= STAGE.PARTICLES)),
      },
      {
        // 代码弹幕：从 PARTICLES 阶段起滚动
        when: (s) => s >= STAGE.PARTICLES,
        make: () => ParticleField({
          rise: durations.rainRise,
          fall: durations.rainFall,
          codeRain: config.codeRain,
          accent: config.accent,
        }),
      },
      {
        // 密度到顶时炸一下白光
        when: (s) => s >= STAGE.PARTICLES,
        make: () => ({ node: h('div', { className: 'dshsplash-flash', style: { animationDuration: '620ms', animationDelay: `${revealDelay}ms` } }) }),
      },
    ]

    const render = () => {
      const revealed = step >= STAGE.PARTICLES
      overlay.setAttribute('data-step', String(step))
      overlay.setAttribute('data-fading', String(revealed))
      overlay.style.animationName = revealed ? 'dshsplash-reveal-out' : 'none'
      // [DSH Mobile] once the reveal starts the page itself turns transparent, so the app shows through the fade
      if (revealed) document.documentElement.classList.add('dshm-reveal')
      const fresh = []
      layers.forEach((layer, position) => {
        const want = layer.when(step)
        if (want && !layer.part) {
          layer.part = layer.make()
          let before = null
          for (let next = position + 1; next < layers.length; next += 1) {
            if (layers[next].part) {
              before = layers[next].part.node
              break
            }
          }
          stage.insertBefore(layer.part.node, before)
          fresh.push(layer.part)
        } else if (!want && layer.part) {
          if (typeof layer.part.destroy === 'function') layer.part.destroy()
          layer.part.node.remove()
          layer.part = null
        } else if (want && layer.part && layer.update) {
          layer.update(layer.part)
        }
      })
      // effects run after the tree is in place, as React's do
      for (const part of fresh) mounted(part)
      if (skipHint !== null) {
        // [DSH Mobile] hint in Traditional Chinese; on a phone a tap outside the login card skips
        skipHint.textContent = step === STAGE.LOGIN || step === STAGE.AUTH
          ? '點 SIGN IN 繼續 · 點別處跳過'
          : '點一下即可跳過'
      }
    }

    /**
     * 开屏驱动：**一个 rAF 循环同时写模糊、亮度与电源黑幕**（unchanged）。
     * 三者读**同一个 `remain`**，所以不可能出现"先亮后清"这类错位。
     */
    const startOpenDriver = () => {
      const driver = scope()
      const duration = Math.max(200, Number(durations.open) || 3200)
      const hold = Math.min(0.8, Math.max(0, Number(durations.lidHoldRatio) || LID_HOLD))
      /** 满模糊半径（px）：大一点才有"糊得看不清"的开场。 */
      const BLUR_MAX = 64
      const begin = performance.now()
      let frame = 0
      let stopped = false
      const tick = (now) => {
        if (stopped) return
        const raw = Math.min(1, (now - begin) / duration)
        // 保持段内一切不动（满模糊 + 全黑）
        const local = raw <= hold ? 0 : (raw - hold) / (1 - hold)
        const eased = local * local * (3 - 2 * local)
        const remain = 1 - eased
        const dawn = document.querySelector('.dshsplash-dawn')
        const screen = document.querySelector('.dshsplash-screen')
        const room = document.querySelector('.dshsplash-room')
        if (room !== null) {
          const blur = (BLUR_MAX * remain).toFixed(2)
          const saturation = (0.55 + 0.45 * eased).toFixed(3)
          room.style.filter = `blur(${blur}px) saturate(${saturation})`
        }
        if (dawn !== null) dawn.style.opacity = remain.toFixed(4)
        // 电源黑幕：保持段恒为 1（纯黑），收敛段前 30% 快速收掉
        if (screen !== null) {
          const screenFade = 0.3
          screen.style.opacity = raw <= hold
            ? '1'
            : String(1 - Math.min(1, (raw - hold) / ((1 - hold) * screenFade)))
        }
        if (raw >= 1) {
          stopped = true
          return
        }
        frame = window.requestAnimationFrame(tick)
      }
      frame = window.requestAnimationFrame(tick)
      driver.add(() => {
        stopped = true
        window.cancelAnimationFrame(frame)
      })
      return driver
    }

    /**
     * 跳过动画（the original's skip effect, re-installed on every stage change）。
     * 登录阶段（LOGIN/AUTH）**不装全局点击跳过**：那一段的点击留给登录按钮。
     * [DSH Mobile] The original skipped on `mousedown` and, during login, on keys only. A phone has no keys, so
     * during login a tap outside the login card skips; a tap on the card never does.
     */
    const installSkip = () => {
      skipLife.dispose()
      skipLife = scope()
      if (!config.allowSkip) return
      const inLogin = step === STAGE.LOGIN || step === STAGE.AUTH
      skipLife.on(window, 'keydown', () => finish())
      if (inLogin) {
        skipLife.on(window, 'pointerdown', (event) => {
          const target = event.target
          if (target instanceof Element && target.closest('.dshsplash-login-card') !== null) return
          finish()
        })
        return
      }
      skipLife.on(window, 'pointerdown', () => finish())
    }

    const setStep = (next) => {
      if (next === step) return
      const previous = step
      step = next
      render()
      if (previous === STAGE.OPEN && openLife !== null) {
        openLife.dispose()
        openLife = null
      }
      if (step === STAGE.OPEN) openLife = startOpenDriver()
      installSkip()
    }

    function finish() {
      if (finished) return
      finished = true
      life.dispose()
      skipLife.dispose()
      if (openLife !== null) openLife.dispose()
      if (loginGate !== null) window.clearTimeout(loginGate)
      for (const layer of layers) {
        if (layer.part && typeof layer.part.destroy === 'function') layer.part.destroy()
        layer.part = null
      }
      // The original unmounted the overlay here (controller.finish → Splash renders null).
      overlay.remove()
      document.documentElement.classList.add('dshm-reveal')
      signalDone()
    }

    const advance = () => {
      if (finished) return
      const moment = timeline[index]
      if (moment === undefined) {
        finish()
        return
      }
      const elapsed = Date.now() - startedAt
      life.timeout(() => {
        if (finished) return
        if (moment.kind === 'loginwait') {
          // 预载跑完，等用户点 SIGN IN：挂起时间线，同时留一个兜底定时器。
          setStep(STAGE.AUTH)
          atLoginGate = true
          if (pendingLoginSkip) {
            pendingLoginSkip = false
            index += 1
            advance()
            return
          }
          loginGate = window.setTimeout(() => {
            loginGate = null
            index += 1
            advance()
          }, (LOGIN_AUTO_CLICK_MS + LOGIN_RIPPLE_MS + 2200) * gate)
          return
        } else if (moment.kind === 'done') {
          finish()
          return
        } else {
          const next = STAGE_OF[moment.kind]
          if (next !== undefined) setStep(next)
        }
        index += 1
        advance()
      }, Math.max(0, moment.at - elapsed))
    }

    /**
     * 供登录回调使用：请求继续播放。
     * [DSH Mobile] "Waiting at login" means the `loginwait` moment has really fired. The original looked at
     * `timeline[index]`, which already points at `loginwait` while that moment is still pending.
     */
    skipTo = (kind) => {
      skipTo = null
      if (!atLoginGate) {
        pendingLoginSkip = true
        return
      }
      const found = timeline.findIndex((item) => item.kind === kind)
      index = found < 0 ? index + 1 : found
      advance()
    }

    document.body.appendChild(overlay)
    setStep(STAGE.BLACK)
    advance()

    // [DSH Mobile] last resort: the planned run plus the whole login gate and a margin
    const ceiling = predictRun(config, gate, userChars.length + LOGIN_PASSWORD_LENGTH)
      + (LOGIN_AUTO_CLICK_MS + LOGIN_RIPPLE_MS + 2200) * gate + 5000
    window.setTimeout(() => finish(), ceiling)
  }

  // ─────────────────────────── [DSH Mobile] reduced motion ───────────────────────────

  /**
   * A short still version: the finished HUD frame of the original (scene, edge frame, title, the user's welcome
   * words and name, the boot log) with every animation and canvas off, held briefly, then a 200 ms fade.
   */
  function playCalm(P) {
    const welcome = P.welcome !== '' ? P.welcome : '歡迎回來'
    const config = { ...FALLBACK, allowSkip: P.skippable }
    const period = resolvePeriod(new Date(), config)
    const FADE_MS = 200
    const hold = P.seconds === null ? 1800 : Math.min(1800, Math.max(800, P.seconds * 1000 - FADE_MS))
    let finished = false

    const scene = Scene({ period, rainDensity: 0, compact: false, picture: P.picture })
    const room = h('div', {
      className: 'dshsplash-room-layer',
      'data-bd': 'true',
      style: { '--dshs-bd-light': String(config.braindanceLight) },
    }, scene)
    const frame = EdgeFrame({ tag: P.number !== '' ? P.number : 'DSH' })
    const hud = Hud({ period, still: true, picture: P.picture, name: P.name, welcome })
    const overlay = h('div', {
      className: 'dshsplash-overlay',
      role: 'presentation',
      'data-scene': SCENE_LAUNCH,
      'data-step': String(STAGE.TEXT),
      'data-period': period,
      'data-fading': 'false',
      style: buildAccentVars(config.accent, config.accentDeep),
    },
    h('div', { className: 'dshsplash-stage' }, room, frame, hud),
    config.allowSkip ? h('div', { className: 'dshsplash-skip', 'data-role': 'skip-hint' }, '點一下即可跳過') : null,
    )
    document.body.appendChild(overlay)
    mounted(scene)
    mounted(hud)

    const end = () => {
      if (finished) return
      finished = true
      document.documentElement.classList.add('dshm-reveal')
      signalDone()
    }
    window.setTimeout(() => {
      overlay.style.transition = `opacity ${FADE_MS}ms linear`
      overlay.style.opacity = '0'
      overlay.addEventListener('transitionend', end, { once: true })
      window.setTimeout(end, FADE_MS + 120)
    }, hold)
    if (config.allowSkip) {
      window.addEventListener('pointerdown', end)
      window.addEventListener('keydown', end)
    }
    window.setTimeout(end, 6000)
  }

  // ─────────────────────────── [DSH Mobile] start ───────────────────────────

  try {
    const P = readParams()
    let calm = P.reduced
    if (calm === null) {
      calm = false
      try {
        calm = Boolean(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
      } catch (_noMedia) { calm = false }
    }
    document.documentElement.classList.toggle('dshm-calm', calm)
    if (calm) playCalm(P)
    else playSplash(P)
  } catch (_failed) {
    signalDone()
  }
})()
