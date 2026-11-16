/**
 * 光點聚形波浪: the boot cut of dsh-opening-free 0.21.1 by bigfatorangecatjohn, hosted on its own page.
 *
 * [DSH Mobile] Adapted from bigfatorangecatjohn/opening-free-0.21.0 @ b75f3e3e5346b48156c2193ca1c11ec3a78a60af,
 * file plugin/dsh-opening/client.src.js. MIT License, Copyright (c) 2026 bigfatorangecatjohn: see LICENSE and
 * NOTICE.md in this folder. Every change from the original is marked [DSH Mobile]; code without the mark is the
 * original's, unchanged apart from indentation.
 *
 * From the original's header: the cut is drawn on one canvas. A data wave sweeps in, bursts into the dots that
 * assemble the mark, the mark freezes into ink inside holographic rings, and the salute rises over a breathing tick
 * bar with a percentage counter. The full sequence adds the five access steps between the rings and the salute; the
 * short cut is the same three beats in 3.8 seconds.
 *
 * [DSH Mobile] Not taken: the dsh plugin wrapper (window.__ModuleLoader__, React, slots, locale), the Settings page and
 * its preview queues, the unused older `Stage` cut, the audio tracks and the DeepSeek whale picture. The mark is the
 * user's picture (picture=1) or a drawn stand-in, and the words are the user's welcome words and name.
 */
(function () {
  'use strict';

  /* ---- [DSH Mobile] the page contract: one done signal ---------------------------------------------------------- */

  let finished = false;
  /** Tells the app the animation is over, once: DSHM.done() and #done. */
  function signalDone() {
    if (finished) return;
    finished = true;
    try {
      if (window.DSHM && typeof window.DSHM.done === 'function') window.DSHM.done();
    } catch (error) {
      /* the host bridge is gone */
    }
    try {
      window.location.hash = '#done';
    } catch (error) {
      /* nothing more to tell */
    }
  }
  // A script error must never leave the app waiting behind a frozen frame.
  window.addEventListener('error', function () {
    signalDone();
  });

  function prefersReducedMotion() {
    try {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (error) {
      return false;
    }
  }

  /* ---- [DSH Mobile] parameters from the query string ------------------------------------------------------------ */

  const MAX_TEXT = 24;
  /** User text: control and direction-override characters removed, at most 24 characters. Only ever used as text. */
  function cleanText(raw) {
    if (raw == null) return '';
    const s = String(raw)
      .replace(/[\u0000-\u001f\u007f-\u009f\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '')
      .trim();
    let parts;
    try {
      parts = Array.from(new Intl.Segmenter('zh-Hant', { granularity: 'grapheme' }).segment(s), function (x) {
        return x.segment;
      });
    } catch (error) {
      parts = Array.from(s);
    }
    return parts.slice(0, MAX_TEXT).join('').trim();
  }

  function readParams() {
    let q;
    try {
      q = new URLSearchParams(window.location.search);
    } catch (error) {
      q = new URLSearchParams('');
    }
    const secs = parseFloat(q.get('seconds'));
    const reducedRaw = q.get('reduced');
    return {
      name: cleanText(q.get('name')),
      welcome: cleanText(q.get('welcome')),
      // number, level and lang are accepted and not shown: this cut has no ID readout, and its words are zh-TW only.
      seconds: isFinite(secs) ? Math.min(30, Math.max(1.5, secs)) : null,
      reduced: reducedRaw === '1' ? true : reducedRaw === '0' ? false : prefersReducedMotion(),
      skippable: q.get('skippable') !== '0',
      picture: q.get('picture') === '1',
      cut: q.get('cut') === 'full' ? 'full' : 'short',
    };
  }

  const P = readParams();

  /* [DSH Mobile] The salute's two lines: 「欢迎回来，」 / 「指挥员」 became the user's welcome words (default 歡迎回來) and
   * name. Without a name the welcome words take the name's place, and the small line above stays empty. */
  const WORDS = (P.welcome || '歡迎回來').replace(/[\s，,。．.！!？?、；;：:…~～]+$/, '') || '歡迎回來';
  const SALUTE_LINES = P.name ? [WORDS + '，', P.name] : ['', WORDS];
  const SPOKEN = P.name ? WORDS + '，' + P.name : WORDS;

  /* ---- [DSH Mobile] stand-in for the host's React -------------------------------------------------------------- */

  /* The original built its DOM with the host's React (`require('react')`). There is no React here: `h` makes real
   * elements so the original's element tree reads as it did. Children are elements or text, and text is only ever a
   * text node, never parsed as HTML. */
  function h(tag, props) {
    const el = document.createElement(tag);
    const p = props || {};
    Object.keys(p).forEach(function (k) {
      const v = p[k];
      if (k === 'key' || v == null) return;
      if (k === 'className') el.className = v;
      else if (k === 'ref') {
        if (typeof v === 'function') v(el);
        else v.current = el;
      } else if (k === 'style') {
        Object.keys(v).forEach(function (s) {
          el.style.setProperty(s, v[s]);
        });
      } else el.setAttribute(k, String(v));
    });
    for (let i = 2; i < arguments.length; i++) append(el, arguments[i]);
    return el;
  }
  function append(el, child) {
    if (child == null || child === false) return;
    if (Array.isArray(child)) {
      child.forEach(function (c) {
        append(el, c);
      });
      return;
    }
    el.appendChild(typeof child === 'object' ? child : document.createTextNode(String(child)));
  }
  function useRef() {
    return { current: null };
  }

  /* ---- the timeline ---------------------------------------------------------------------------------------------- */

  /* [DSH Mobile] The two Chinese cues exactly as tools/build-client.mjs inlined them from assets/boot-zh-short.sequence.json
   * and assets/boot-zh.sequence.json, without the audio track. Step captions are in Traditional Chinese; the other
   * three languages are not carried. */
  const CUES = {
    short: {
      lang: 'zh',
      short: true,
      total: 3.808,
      leadIn: 3.808,
      fadeOut: 0.28,
      mark: { at: 0, dur: 0 },
      phases: { sweep: [0, 0.52], burst: 0.52, whale: [0.58, 2.03], rings: 1.06, salute: 1.62, name: 2.763 },
      steps: [],
      salute: { lang: 'zh', at: 1.62, end: 3.507 },
    },
    full: {
      lang: 'zh',
      short: false,
      total: 18,
      leadIn: 2.6,
      fadeOut: 0.52,
      mark: { at: 1.06, dur: 1.54 },
      phases: { sweep: [0, 0.78], burst: 0.78, whale: [0.8, 2.35], rings: 1.95, burstPower: 0.35 },
      steps: [
        { at: 2.6, end: 3.677, text: { zh: '需要訪問許可' } },
        { at: 4.939, end: 6.101, text: { zh: '身份資料確認' } },
        { at: 7.364, end: 8.521, text: { zh: '接收訪問需求' } },
        { at: 9.783, end: 10.906, text: { zh: '開始讀取權限' } },
        { at: 12.168, end: 13.65, text: { zh: '讀取完畢，核驗通過' } },
      ],
      salute: { lang: 'zh', at: 15.481, end: 16.7 },
    },
  };

  /**
   * Tracking is language-specific; these are the settled (fully arrived) values for Chinese. The tick opens them wider
   * and closes to this.
   */
  const LANG_TRACK = {
    lead: { zh: 0.42 },
    name: { zh: 0.26 },
  };

  /**
   * The short cut carries its own map (`phases`) from the audio master: sweep -> burst -> whale -> rings ->
   * salute/name. Nothing in the short cut reads `mark`; that field belongs to the full sequence alone.
   */
  function phaseOf(cue) {
    const raw = cue.phases;
    const salute = Array.isArray(cue.salute) ? cue.salute[0] : cue.salute;
    const saluteAt = salute && typeof salute.at === 'number' ? salute.at : 1.4;
    /* The name is spoken at the end of the salute line, not somewhere in the tail after it: 72% into the recorded
     * phrase is where the address starts. Falling back to the track total pushed the name past the voice. */
    const saluteEnd = salute && typeof salute.end === 'number' ? salute.end : cue.total || 3.4;
    const nameAt = saluteAt + (saluteEnd - saluteAt) * 0.72;
    /* burstPower lets the long sequence keep the same three beats with a quieter unlock: its audio has no impact,
     * only a chime to land on. */
    const power = raw && typeof raw.burstPower === 'number' ? raw.burstPower : 1;
    if (raw && raw.sweep && raw.whale && typeof raw.burst === 'number') {
      return {
        sweep: raw.sweep,
        burst: raw.burst,
        whale: raw.whale,
        rings: typeof raw.rings === 'number' ? raw.rings : raw.sweep[1] + 0.47,
        salute: typeof raw.salute === 'number' ? raw.salute : saluteAt,
        name: typeof raw.name === 'number' ? raw.name : nameAt,
        burstPower: power,
      };
    }
    return {
      sweep: [0, 0.55],
      burst: 0.55,
      whale: [0.6, 1.55],
      rings: 1.02,
      salute: saluteAt,
      name: nameAt,
      burstPower: power,
    };
  }

  /** A step caption; [DSH Mobile] only the Chinese text is carried. */
  function stepTextOf(step, lang) {
    const text = step && step.text;
    if (!text) return '';
    return text[lang] || text.zh || '';
  }

  /**
   * One mode is everything the cut needs to play: its cue, its language, its beats, its tracking.
   * [DSH Mobile] Picked by the `cut` parameter instead of the stored Settings choice, and without a track.
   */
  function modeOf(key) {
    const cue = CUES[key] || CUES.short;
    const lang = cue.lang || 'zh';
    return {
      key: key,
      cue: cue,
      lang: lang,
      short: cue.short === true,
      lines: SALUTE_LINES,
      phase: phaseOf(cue),
      leadTrack: LANG_TRACK.lead[lang] !== undefined ? LANG_TRACK.lead[lang] : 0.42,
      nameTrack: LANG_TRACK.name[lang] !== undefined ? LANG_TRACK.name[lang] : 0.26,
    };
  }

  const MODE = modeOf(P.cut);

  const PALETTE = {
    stage: '#f7f8fa',
    ink: '#0b0d10',
    dim: '#5b6672',
    rule: 'rgba(11, 13, 16, 0.14)',
    accent: '#4d6bfe',
    faint: 'rgba(11, 13, 16, 0.28)',
  };

  /** How many strokes the short cut draws along the bottom edge. */
  const BAR_COUNT = 44;

  /* ---- the short cut --------------------------------------------------------------------------------------------- */

  function clampUnit(value) {
    return value < 0 ? 0 : value > 1 ? 1 : value;
  }

  function ramp(edge0, edge1, value) {
    const k = clampUnit((value - edge0) / (edge1 - edge0));
    return k * k * (3 - 2 * k);
  }

  function mix(a, b, k) {
    return a + (b - a) * k;
  }

  /* [DSH Mobile] The mark's silhouette in the 1024 px work tile: a disc of this radius at the centre. */
  const MARK_R = 400;

  /**
   * [DSH Mobile] Where the stage sits. The original put the rings at 0.44 of the height and sized everything by the
   * shorter side, which on a phone held sideways (about 915 x 412) leaves no room under the rings: its caption is then
   * pushed up onto the lowest ring. On such short, wide screens the whole stage is drawn at 0.8 of that size and a
   * little higher (rings at 0.38 of the height), so the salute lands under the rings as it does upright. The salute and
   * the bar need about 124 px under the rings (see place() and the short-screen rule in wave.css), so on the shortest
   * of these screens (under about 350 px tall, e.g. a phone held sideways with the status bar showing) the stage is
   * drawn a little smaller still, never under half the height. The canvas and the caption both use this, and the
   * caption marks the stage `is-compact` for wave.css (not a CSS height query: a WebView laid out at wrap-content
   * height can match every height query).
   */
  function stageOf(w, h) {
    const compact = h < 560 && w > h * 1.2;
    if (!compact) return { compact: false, unit: Math.min(w, h), cy: 0.44, burst: 0.5 };
    const unit = Math.max(h * 0.5, Math.min(Math.min(w, h) * 0.8, (h * 0.62 - 124) / 0.335));
    return { compact: true, unit: unit, cy: 0.38, burst: 0.44 };
  }

  /** [DSH Mobile] The first character of the name (or of the welcome words), cut out of the stand-in mark. */
  function initialOf(name) {
    if (!name) return '';
    try {
      const first = new Intl.Segmenter('zh-Hant', { granularity: 'grapheme' }).segment(name)[Symbol.iterator]().next();
      return first.done ? '' : first.value.segment;
    } catch (error) {
      return Array.from(name)[0] || '';
    }
  }

  /**
   * [DSH Mobile] Was prepareWhale(url). The original read the DeepSeek whale PNG (a dark whale on a pale tile) and lifted
   * the whale out by luminance. That picture is not shipped. The silhouette is now drawn here, black on clear, and goes
   * through the original's same threshold and bounding-box loop:
   *  - with the user's picture: a disc, and the ink body is the picture (cover-fitted) instead of near-black;
   *  - without one: the same disc in the original's near-black ink, with the first character of the name (or of the
   *    welcome words) cut out the way the whale's belly and eye were.
   * Everything after the silhouette is the original's: ink dress, silver rims, halo, scan blade and the ~1800 sample
   * points the dots converge to. It runs before the clock starts instead of while the wave plays.
   */
  function prepareMark(picture, initial) {
    const state = { ready: false, ink: null, rim: null, rimB: null, glow: null, blade: null, box: null, points: [] };
    try {
      const size = 1024;
      const work = document.createElement('canvas');
      work.width = size;
      work.height = size;
      const wctx = work.getContext('2d');
      // [DSH Mobile] the drawn silhouette, in place of `wctx.drawImage(img, 0, 0, size, size)`
      wctx.fillStyle = '#000000';
      wctx.beginPath();
      wctx.arc(size / 2, size / 2, MARK_R, 0, 6.2831853);
      wctx.fill();
      if (!picture && initial) {
        wctx.save();
        wctx.globalCompositeOperation = 'destination-out';
        wctx.font = '700 460px system-ui, "Noto Sans CJK TC", "Noto Sans TC", sans-serif';
        wctx.textAlign = 'center';
        wctx.textBaseline = 'alphabetic';
        const m = wctx.measureText(initial);
        const asc = m.actualBoundingBoxAscent || 340;
        const desc = m.actualBoundingBoxDescent || 60;
        const left = m.actualBoundingBoxLeft || 0;
        const right = m.actualBoundingBoxRight || 0;
        wctx.fillText(initial, size / 2 + (left - right) / 2, size / 2 + (asc - desc) / 2);
        wctx.restore();
      }
      const pixels = wctx.getImageData(0, 0, size, size).data;

      const mask = document.createElement('canvas');
      mask.width = size;
      mask.height = size;
      const mctx = mask.getContext('2d');
      const masked = mctx.createImageData(size, size);
      let minX = size;
      let minY = size;
      let maxX = 0;
      let maxY = 0;
      for (let i = 0; i < pixels.length; i += 4) {
        const lum = 0.299 * pixels[i] + 0.587 * pixels[i + 1] + 0.114 * pixels[i + 2];
        const on = pixels[i + 3] > 128 && lum < 150;
        masked.data[i] = 255;
        masked.data[i + 1] = 255;
        masked.data[i + 2] = 255;
        masked.data[i + 3] = on ? 255 : 0;
        if (!on) continue;
        const at = i / 4;
        const x = at % size;
        const y = (at - x) / size;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
      mctx.putImageData(masked, 0, 0);

      const box = {
        x: minX,
        y: minY,
        w: Math.max(1, maxX - minX),
        h: Math.max(1, maxY - minY),
      };
      box.cx = minX + box.w / 2;
      box.cy = minY + box.h / 2;

      // What the mark freezes into: ink, not blue. Black stays black -- the cyberpunk is in the surface: a cold blue
      // nebula off the head, a violet one off the tail, CRT scanlines and a hard highlight along the top edge, all
      // clipped to the silhouette.
      const ink = document.createElement('canvas');
      ink.width = size;
      ink.height = size;
      const ictx = ink.getContext('2d');
      /* [DSH Mobile] With the user's picture the body is the picture, cropped to a square (from the middle; a tall
       * picture from a little above the middle, where faces usually are) and laid over the silhouette's box. The
       * nebulas then go on at a third of their strength so the picture keeps its own colours. avatar.img (see boot)
       * is already square, so it is used whole; the crop only applies to the picture.img fallback. */
      let dress = 1;
      if (picture) {
        const iw = picture.naturalWidth;
        const ih = picture.naturalHeight;
        const side = Math.min(iw, ih);
        const sx = (iw - side) / 2;
        const sy = ih > iw ? (ih - side) * 0.35 : (ih - side) / 2;
        ictx.fillStyle = '#141922';
        ictx.fillRect(0, 0, size, size);
        ictx.drawImage(picture, sx, sy, side, side, minX, minY, box.w, box.h);
        dress = 0.35;
      } else {
        const inkGrad = ictx.createLinearGradient(0, minY, 0, maxY);
        inkGrad.addColorStop(0, '#141922');
        inkGrad.addColorStop(0.55, '#090c12');
        inkGrad.addColorStop(1, '#030508');
        ictx.fillStyle = inkGrad;
        ictx.fillRect(0, 0, size, size);
      }

      const headGlow = ictx.createRadialGradient(
        minX + box.w * 0.30, minY + box.h * 0.26, 0,
        minX + box.w * 0.30, minY + box.h * 0.26, box.w * 0.56,
      );
      headGlow.addColorStop(0, 'rgba(52, 96, 255, ' + (0.50 * dress).toFixed(3) + ')');
      headGlow.addColorStop(0.45, 'rgba(40, 78, 224, ' + (0.18 * dress).toFixed(3) + ')');
      headGlow.addColorStop(1, 'rgba(40, 78, 224, 0)');
      ictx.fillStyle = headGlow;
      ictx.fillRect(0, 0, size, size);

      const tailGlow = ictx.createRadialGradient(
        minX + box.w * 0.74, minY + box.h * 0.74, 0,
        minX + box.w * 0.74, minY + box.h * 0.74, box.w * 0.52,
      );
      tailGlow.addColorStop(0, 'rgba(132, 48, 232, ' + (0.40 * dress).toFixed(3) + ')');
      tailGlow.addColorStop(0.5, 'rgba(112, 40, 210, ' + (0.14 * dress).toFixed(3) + ')');
      tailGlow.addColorStop(1, 'rgba(112, 40, 210, 0)');
      ictx.fillStyle = tailGlow;
      ictx.fillRect(0, 0, size, size);

      ictx.fillStyle = 'rgba(118, 226, 255, 0.075)';
      for (let line = minY; line < maxY; line += 4) {
        ictx.fillRect(0, line, size, 1);
      }

      const topSheen = ictx.createLinearGradient(0, minY, 0, minY + box.h * 0.30);
      topSheen.addColorStop(0, 'rgba(178, 232, 255, 0.20)');
      topSheen.addColorStop(1, 'rgba(178, 232, 255, 0)');
      ictx.fillStyle = topSheen;
      ictx.fillRect(0, minY, size, box.h * 0.30);

      ictx.globalCompositeOperation = 'destination-in';
      ictx.drawImage(mask, 0, 0);
      ictx.globalCompositeOperation = 'source-over';

      // Neon edge: the silhouette, eroded by about 1.8%, leaves a band that traces both the outline and the cut-outs.
      const rimSize = 512;
      const rimScale = rimSize / size;
      function buildRim(top, mid, bottom) {
        const out = document.createElement('canvas');
        out.width = rimSize;
        out.height = rimSize;
        const actx = out.getContext('2d');
        actx.drawImage(mask, 0, 0, rimSize, rimSize);
        actx.globalCompositeOperation = 'destination-out';
        actx.translate(rimSize / 2, rimSize / 2);
        actx.scale(0.982, 0.982);
        actx.translate(-rimSize / 2, -rimSize / 2);
        actx.drawImage(mask, 0, 0, rimSize, rimSize);
        actx.setTransform(1, 0, 0, 1, 0, 0);
        actx.globalCompositeOperation = 'source-in';
        const grad = actx.createLinearGradient(0, minY * rimScale, 0, maxY * rimScale);
        grad.addColorStop(0, top);
        grad.addColorStop(0.52, mid);
        grad.addColorStop(1, bottom);
        actx.fillStyle = grad;
        actx.fillRect(0, 0, rimSize, rimSize);
        return out;
      }
      /* Semi-transparent metallic silver: a bright face and a steel shadow face, so the edge reads as polished metal
       * rather than a neon tube. Both are laid over the black body at partial alpha. */
      const rim = buildRim('#ffffff', '#c9d3e2', '#8b97ab');
      const rimB = buildRim('#e6edf8', '#8b98ac', '#5f6a7c');

      // Halo: the mask smeared out by repeated small offsets, then tinted.
      const haloSize = 160;
      const haloScale = haloSize / size;
      const glow = document.createElement('canvas');
      glow.width = haloSize;
      glow.height = haloSize;
      const gctx = glow.getContext('2d');
      gctx.globalAlpha = 0.075;
      for (let gy = -3; gy <= 3; gy++) {
        for (let gx = -3; gx <= 3; gx++) {
          if (!gx && !gy) continue;
          gctx.drawImage(
            mask, gx * 2.7, gy * 2.7, haloSize, haloSize,
          );
        }
      }
      gctx.globalAlpha = 1;
      gctx.globalCompositeOperation = 'source-in';
      const haloGrad = gctx.createLinearGradient(0, minY * haloScale, 0, maxY * haloScale);
      /* Top and bottom are deliberately not near-white: a vertical white-to-steel-to-white ramp left the north and
       * south edges of the halo almost invisible, which read as a flat cut-out. */
      haloGrad.addColorStop(0, '#cdd9e9');
      haloGrad.addColorStop(0.5, '#8fa1ba');
      haloGrad.addColorStop(1, '#d8e2f1');
      gctx.fillStyle = haloGrad;
      gctx.fillRect(0, 0, haloSize, haloSize);

      /* The scan blade that rides down the mark as it lands, in the same metal as the rim. */
      const blade = document.createElement('canvas');
      blade.width = size;
      blade.height = size;
      const blctx = blade.getContext('2d');
      const bladeGrad = blctx.createLinearGradient(0, minY, 0, maxY);
      bladeGrad.addColorStop(0, '#ffffff');
      bladeGrad.addColorStop(0.5, '#dde6f3');
      bladeGrad.addColorStop(1, '#a9b6c9');
      blctx.fillStyle = bladeGrad;
      blctx.fillRect(0, 0, size, size);
      blctx.globalCompositeOperation = 'destination-in';
      blctx.drawImage(mask, 0, 0);

      const sample = [];
      // 6 px on a 1024 tile: about 1800 dots, dense enough to read as a scanned shape and still cheap to animate.
      const step = 6;
      for (let y = minY; y < maxY; y += step) {
        for (let x = minX; x < maxX; x += step) {
          const at = ((y | 0) * size + (x | 0)) * 4 + 3;
          if (masked.data[at] < 128) continue;
          sample.push({ x: (x - box.cx) / box.w, y: (y - box.cy) / box.w });
        }
      }

      const keep = 1800;
      let points = sample;
      if (sample.length > keep) {
        const stride = sample.length / keep;
        points = [];
        for (let i = 0; i < keep; i++) points.push(sample[Math.floor(i * stride)]);
      }
      for (let i = 0; i < points.length; i++) {
        const p = points[i];
        const angle = Math.random() * 6.2831853;
        const reach = 0.12 + Math.random() * 0.55;
        p.sx = Math.cos(angle) * reach;
        p.sy = Math.sin(angle) * reach * 0.8;
        p.r = 0.5 + Math.random() * 1.5;
        p.sw = (Math.random() - 0.5) * 1.6;
        p.delay = Math.random();
        p.tone = Math.random();
        // Static per point: the swim stroke weights the tail, and mixing it at sample time keeps a pow() out of the
        // per-frame dot loop.
        p.tailK = Math.pow(clampUnit(p.x + 0.5), 1.7);
      }

      state.points = points;
      state.ink = ink;
      state.rim = rim;
      state.rimB = rimB;
      state.glow = glow;
      state.blade = blade;
      state.box = box;
      state.ready = true;
    } catch (error) {
      /* no 2d context: the cut runs without the mark */
    }
    return state;
  }

  /** One canvas for the whole short cut: stream, burst, mark, rings. */
  function makeCyberScene(canvas, phase, whale) {
    // [DSH Mobile] `whale` is the prepared mark (prepareMark), passed in; the original called prepareWhale(MARK) here.
    // Kept transparent: the stage carries a soft white radial glow under the canvas, and an opaque surface would have
    // to repaint it every frame.
    const ctx = canvas.getContext('2d');
    const dust = [];
    let w = 0;
    let h = 0;
    let unit = 0;
    let stage = stageOf(1, 1); // [DSH Mobile] see stageOf

    /* ---- batched dots -------------------------------------------------
     * Every particle used to be its own beginPath / arc / fill: ~900 fills a frame for the water, ~1800 for the
     * mark, ~300 for the rings. Three thousand canvas state flushes per frame is where the stutter came from.
     * Instead each pass drops its points into this shared buffer and they come out as one path per (colour, alpha
     * bucket) pair -- around fifty fills a frame instead of three thousand.
     *
     * Dots stay circles of the exact same radius as before; only their alpha is quantised, and 24 buckets means the
     * worst error is ±0.02, well under a display step. Buckets are filled by counting sort in two O(n) passes, so the
     * bucket count costs nothing per dot. */
    const DOT_MAX = 2600;
    const DOT_BUCKETS = 24;
    const DOT_BINS = 64;
    const dotX = new Float32Array(DOT_MAX);
    const dotY = new Float32Array(DOT_MAX);
    const dotR = new Float32Array(DOT_MAX);
    const dotA = new Float32Array(DOT_MAX);
    const dotC = new Uint8Array(DOT_MAX);
    const dotB = new Uint8Array(DOT_MAX);
    const dotOrder = new Int32Array(DOT_MAX);
    const binStart = new Int32Array(DOT_BINS + 1);
    const binCursor = new Int32Array(DOT_BINS + 1);
    let dotN = 0;

    function dotAdd(x, y, r, a, c) {
      if (a <= 0.012 || dotN >= DOT_MAX) return;
      dotX[dotN] = x;
      dotY[dotN] = y;
      dotR[dotN] = r;
      dotA[dotN] = a > 1 ? 1 : a;
      dotC[dotN] = c || 0;
      dotN += 1;
    }

    function dotPaint(colors, buckets) {
      const n = dotN;
      if (!n) return;
      const bins = Math.min(DOT_BINS, colors.length * buckets);
      for (let i = 0; i <= bins; i++) binStart[i] = 0;
      for (let i = 0; i < n; i++) {
        let b = (dotA[i] * buckets) | 0;
        if (b >= buckets) b = buckets - 1;
        const bin = dotC[i] * buckets + b;
        dotB[i] = bin;
        binStart[bin + 1] += 1;
      }
      for (let i = 0; i < bins; i++) binStart[i + 1] += binStart[i];
      for (let i = 0; i < bins; i++) binCursor[i] = binStart[i];
      for (let i = 0; i < n; i++) dotOrder[binCursor[dotB[i]]++] = i;

      for (let c = 0; c < colors.length; c++) {
        for (let b = 0; b < buckets; b++) {
          const bin = c * buckets + b;
          const from = binStart[bin];
          const to = binStart[bin + 1];
          if (from === to) continue;
          ctx.beginPath();
          for (let k = from; k < to; k++) {
            const i = dotOrder[k];
            ctx.moveTo(dotX[i] + dotR[i], dotY[i]);
            ctx.arc(dotX[i], dotY[i], dotR[i], 0, 6.2831853);
          }
          ctx.fillStyle = 'rgba(' + colors[c] + ', ' + ((b + 0.5) / buckets).toFixed(3) + ')';
          ctx.fill();
        }
      }
      dotN = 0;
    }

    function resize() {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      w = canvas.clientWidth || window.innerWidth;
      h = canvas.clientHeight || window.innerHeight;
      stage = stageOf(w, h);
      unit = stage.unit; // [DSH Mobile] was Math.min(w, h)
      canvas.width = Math.max(1, Math.round(w * dpr));
      canvas.height = Math.max(1, Math.round(h * dpr));
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      if (!dust.length) {
        const count = Math.max(80, Math.min(210, Math.round((w * h) / 11000)));
        for (let i = 0; i < count; i++) {
          dust.push({
            x: Math.random(),
            y: Math.random(),
            r: 0.6 + Math.random() * 1.7,
            ph: Math.random() * 6.2831853,
            sp: 0.5 + Math.random() * 1.6,
            a: 0.18 + Math.random() * 0.34,
          });
        }
      }
    }

    /** One prepared layer of the mark, drawn at a given width, optionally spun. */
    function paintArt(art, x, y, widthPx, alpha, rot, ox, oy) {
      if (!whale.ready || !art || alpha <= 0.004) return;
      const scale = widthPx / whale.box.w;
      const dx = x - whale.box.cx * scale + (ox || 0);
      const dy = y - whale.box.cy * scale + (oy || 0);
      ctx.save();
      ctx.globalAlpha = Math.min(1, alpha);
      if (rot) {
        ctx.translate(x, y);
        ctx.rotate(rot);
        ctx.translate(-x, -y);
      }
      ctx.drawImage(art, dx, dy, 1024 * scale, 1024 * scale);
      ctx.restore();
    }

    /**
     * The mark in its final dress: black body, blue nebula and CRT scanlines baked into it, a neon edge that splits
     * into magenta while it lands, a breathing halo, and -- during materialisation only -- a scan blade riding down
     * the silhouette plus a few torn slices.
     */
    function paintMark(x, y, widthPx, alpha, rot, tt, settled, pulse) {
      if (!whale.ready || alpha <= 0.004) return;
      const scale = widthPx / whale.box.w;
      const dx = x - whale.box.cx * scale;
      const dy = y - whale.box.cy * scale;
      const span = 1024 * scale;

      /* Two halo passes: a wide silver cloud that reads from across the room, and a tight one that hugs the
       * silhouette so the edge looks lit rather than pasted. */
      paintArt(whale.glow, x, y, widthPx * 1.18, alpha * (0.29 + 0.22 * pulse));
      paintArt(whale.glow, x, y, widthPx * 1.05, alpha * (0.53 + 0.26 * pulse));
      paintArt(whale.ink, x, y, widthPx, alpha, rot);
      if (whale.rim) {
        const split = 0.6 + 2.4 * (1 - settled);
        paintArt(whale.rimB, x, y, widthPx, alpha * 0.39, rot, split, -split * 0.45);
        paintArt(whale.rim, x, y, widthPx, alpha * (0.65 + 0.20 * pulse), rot, -split * 0.55, split * 0.35);
      }

      /* The blade: one bright pass down the body as the ink bleeds in. */
      if (alpha > 0.02 && alpha < 0.995) {
        const blade = whale.box.y + whale.box.h * alpha - 10;
        ctx.save();
        ctx.globalAlpha = 0.5 * Math.sin(Math.PI * alpha);
        ctx.globalCompositeOperation = 'lighter';
        ctx.drawImage(whale.blade, 0, blade, 1024, 20, dx, dy + blade * scale, span, 20 * scale);
        ctx.restore();
      }

      /* Torn slices: strongest while landing, then an occasional stutter so the mark never reads as a flat pasted
       * bitmap. */
      const stutter = Math.max(0, Math.sin(tt * 2.3) - 0.94) * 6;
      const tear = (1 - settled) * 3.4 + stutter;
      if (tear > 0.05 && alpha > 0.08) {
        ctx.save();
        ctx.globalAlpha = Math.min(1, alpha);
        const bands = [[0.17, 0.05, 1], [0.45, 0.038, -1], [0.71, 0.046, 1]];
        for (let i = 0; i < bands.length; i++) {
          const sy = whale.box.y + whale.box.h * bands[i][0];
          const sh = whale.box.h * bands[i][1];
          const shift = Math.sin(tt * 7.7 + i * 2.3) * tear * bands[i][2];
          ctx.drawImage(whale.ink, 0, sy, 1024, sh, dx + shift, dy + sy * scale, span, sh * scale);
        }
        ctx.restore();
      }
    }

    /**
     * The data stream: one wave, made of grain.
     *
     * A single breaking crest spanning the screen, and everything that used to be a flat gradient is a field of
     * particles riding the surface: body grain, a bright crest file, and spray thrown off the lip. The ripples run at
     * 1.15 / 1.85 / 2.60 rad/s, which is what makes the water roll like water instead of flicker like a scanner.
     */
    /* The body and lip gradients only depend on where the wave sits and how big the screen is, so they are built once
     * per size instead of every frame; the fade rides on globalAlpha instead. */
    let gradKey = '';
    let bodyGrad = null;
    let edgeGrad = null;
    function waveGrads(by) {
      const key = by + ':' + unit;
      if (key === gradKey && bodyGrad) return;
      gradKey = key;
      bodyGrad = ctx.createLinearGradient(0, by - unit * 0.2, 0, by + unit * 0.15);
      bodyGrad.addColorStop(0, 'rgba(30, 52, 205, 0.60)');
      bodyGrad.addColorStop(0.45, 'rgba(62, 90, 240, 0.48)');
      bodyGrad.addColorStop(1, 'rgba(124, 149, 255, 0.18)');
      edgeGrad = ctx.createLinearGradient(0, by - unit * 0.24, 0, by + unit * 0.18);
      edgeGrad.addColorStop(0, 'rgba(150, 178, 255, 0.90)');
      edgeGrad.addColorStop(0.55, 'rgba(28, 48, 200, 0.85)');
      edgeGrad.addColorStop(1, 'rgba(28, 48, 200, 0)');
    }

    function drawWave(head, alpha, t, by) {
      // One wave, not a stripe with a bump on the end. 0.72 screen widths keeps the trailing slope short enough to
      // read as the back of the same wave, and the amplitude grows with the distance from the tail.
      const tail = head - 0.72 * w;
      const span = Math.max(1, head - tail);
      const reach = function (x) {
        return clampUnit((x - tail) / span);
      };
      const amp = function (x) {
        return unit * (0.005 + 0.158 * Math.pow(reach(x), 1.25));
      };
      const crest = function (x) {
        const k = reach(x);
        return (
          by -
          amp(x) +
          Math.sin(x * 0.0082 + t * 1.15) * unit * 0.024 * (0.3 + k) +
          Math.sin(x * 0.0181 - t * 1.85 + 0.8) * unit * 0.010 * (0.22 + k) +
          Math.sin(x * 0.0410 + t * 2.60 + 1.7) * unit * 0.0028
        );
      };
      const trough = function (x) {
        const k = reach(x);
        return (
          by +
          amp(x) * 0.55 +
          Math.sin(x * 0.0077 + t * 1.15 + 1.3) * unit * 0.019 * (0.3 + k) +
          Math.sin(x * 0.0197 - t * 1.95 + 2.1) * unit * 0.0072 * (0.22 + k)
        );
      };

      ctx.save();
      ctx.globalCompositeOperation = 'source-over';

      /* ---- the body: a soft blue mass the grain reads against --------- */
      const lipX = head + unit * 0.022;
      ctx.beginPath();
      ctx.moveTo(tail, by);
      for (let x = tail; x <= head; x += 6) ctx.lineTo(x, crest(x));
      ctx.quadraticCurveTo(lipX, crest(head) + unit * 0.035, lipX, by - amp(head) * 0.28);
      ctx.quadraticCurveTo(head + unit * 0.012, by + amp(head) * 0.34, head - unit * 0.04, by + unit * 0.03);
      for (let x = head - unit * 0.04; x >= tail; x -= 6) ctx.lineTo(x, trough(x));
      ctx.closePath();
      waveGrads(by);
      ctx.globalAlpha = alpha;
      ctx.fillStyle = bodyGrad;
      ctx.fill();
      ctx.globalAlpha = 1;

      /* ---- body grain -------------------------------------------------- */
      const GRAIN = 620;
      for (let i = 0; i < GRAIN; i++) {
        const s1 = Math.sin(i * 12.9898) * 0.5 + 0.5;
        const s2 = Math.sin(i * 78.233 + 1.7) * 0.5 + 0.5;
        const s3 = Math.sin(i * 39.346 + 4.1) * 0.5 + 0.5;
        const k = 1 - Math.pow(1 - (i + 0.5) / GRAIN, 1.7);
        const px0 = tail + span * k + Math.sin(t * 0.9 + s2 * 6.2831853) * unit * 0.006 + (s3 - 0.5) * unit * 0.01;
        const v = Math.pow(s1, 1.35);
        const py =
          crest(px0) +
          (trough(px0) - crest(px0)) * v +
          Math.sin(t * 1.6 + s2 * 6.2831853 + px0 * 0.01) * unit * 0.004;
        const tw = 0.5 + 0.5 * Math.sin(t * 2.4 + s3 * 6.2831853);
        const a = alpha * (0.1 + 0.34 * (1 - v) * (0.35 + 0.65 * k)) * (0.55 + 0.45 * tw);
        const light = s1 > 0.66;
        dotAdd(px0, py, (0.5 + s2 * 1.6) * (0.6 + 0.5 * k), light ? a : a * 0.85, light ? 1 : 0);
      }
      dotPaint(['24, 44, 190', '198, 216, 255'], DOT_BUCKETS);

      /* ---- the crest file: bright grain along the breaking lip --------- */
      const FILED = 230;
      for (let i = 0; i < FILED; i++) {
        const s1 = Math.sin(i * 21.7 + 0.5) * 0.5 + 0.5;
        const s2 = Math.sin(i * 57.31 + 2.3) * 0.5 + 0.5;
        const k = 1 - Math.pow(1 - (i + 0.5) / FILED, 2.4);
        const px = tail + span * k + Math.sin(t * 1.4 + s2 * 6.2831853) * unit * 0.004;
        const py = crest(px) + (s1 - 0.5) * unit * 0.02 * (0.35 + k);
        const tw = 0.45 + 0.55 * Math.sin(t * 3.1 + s1 * 6.2831853);
        const a = alpha * (0.16 + 0.62 * k) * tw;
        dotAdd(px, py, 0.6 + s2 * 2.1 * (0.35 + k), a, 0);
      }
      dotPaint(['228, 238, 255'], DOT_BUCKETS);

      /* ---- spray thrown off the lip ------------------------------------ */
      for (let i = 0; i < 44; i++) {
        const s1 = Math.sin(i * 33.1 + 0.7) * 0.5 + 0.5;
        const phase = (t * 0.52 + s1) % 1;
        const ox = head - unit * (0.02 + 0.1 * s1);
        const px = ox + unit * (0.03 + 0.1 * s1) * phase;
        const py = crest(ox) - unit * 0.012 + unit * (0.05 + 0.17 * s1) * phase * phase;
        const a = alpha * (1 - phase) * 0.55;
        dotAdd(px, py, 0.7 + (1 - phase) * 1.8, a, 0);
      }
      dotPaint(['108, 138, 252'], DOT_BUCKETS);

      /* ---- the lip itself, so the head keeps a hard edge ---------------- */
      ctx.globalAlpha = alpha;
      ctx.strokeStyle = edgeGrad;
      ctx.lineWidth = 2.8;
      ctx.beginPath();
      ctx.moveTo(head - unit * 0.06, crest(head - unit * 0.06));
      ctx.quadraticCurveTo(head + unit * 0.035, crest(head) - unit * 0.035, head + unit * 0.02, by);
      ctx.stroke();
      ctx.globalAlpha = 1;

      for (let i = 1; i <= 6; i++) {
        const ry = unit * (0.006 + i * 0.0055);
        ctx.beginPath();
        ctx.ellipse(head - i * unit * 0.055, by + unit * 0.012, ry * 3.2, ry, 0, 0, 6.2831853);
        ctx.strokeStyle = 'rgba(77, 107, 254, ' + (alpha * 0.18 * (1 - i / 7)).toFixed(3) + ')';
        ctx.lineWidth = 0.8;
        ctx.stroke();
      }

      const halo = ctx.createRadialGradient(head, by, 0, head, by, unit * 0.26);
      halo.addColorStop(0, 'rgba(77, 107, 254, ' + (0.2 * alpha).toFixed(3) + ')');
      halo.addColorStop(1, 'rgba(77, 107, 254, 0)');
      ctx.fillStyle = halo;
      ctx.fillRect(head - unit * 0.26, by - unit * 0.26, unit * 0.52, unit * 0.52);

      ctx.restore();
    }

    function frame(t) {
      if (!w || !h) return;
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = 'source-over';
      ctx.clearRect(0, 0, w, h);

      const cx = w / 2;
      const cy = h * stage.cy; // [DSH Mobile] was h * 0.44
      const burstY = h * stage.burst; // [DSH Mobile] was h * 0.5
      // Rings one size up, mark half a size down: the mark sits clearly inside the innermost ring with a margin
      // instead of grazing it.
      const ringR = Math.min(unit * 0.33, 360);
      const swimW = Math.min(unit * 0.46, 495);
      const restW = Math.min(unit * 0.315, 330);
      const breath = 0.5 + 0.5 * Math.sin(6.2831853 * 0.32 * (t - 0.9) - 1.1);
      const alive = 0.55 + 0.45 * breath;

      for (let i = 0; i < dust.length; i++) {
        const d = dust[i];
        const drift = d.y - t * 0.014 * d.sp;
        const y = drift - Math.floor(drift);
        const x = d.x + Math.sin(t * 0.8 * d.sp + d.ph) * 0.006;
        const fade = 0.5 + 0.5 * Math.sin(t * 1.2 * d.sp + d.ph);
        const a = d.a * (0.1 + 0.9 * fade * fade) * ramp(0, 0.5, t);
        dotAdd(x * w, y * h, d.r, a, 0);
      }
      dotPaint(['134, 158, 255'], DOT_BUCKETS);

      // The head is still at the centre on the burst frame, but it eases in and out instead of launching at full
      // speed, and after the burst the water keeps travelling for over half a second while it thins out.
      if (t < phase.burst + 0.72) {
        const sweep = clampUnit(t / Math.max(0.001, phase.burst));
        const head =
          t < phase.burst
            ? mix(-0.22 * w, 0.54 * w, sweep * sweep * (3 - 2 * sweep))
            : mix(0.54 * w, 1.46 * w, clampUnit((t - phase.burst) / 0.62));
        const fade =
          t < phase.burst
            ? ramp(0, 0.14, t)
            : Math.max(0, 1 - (t - phase.burst) / 0.62);
        if (fade > 0.01) drawWave(head, fade, t, burstY);
      }

      const bt = t - phase.burst;
      /* The long sequence unlocks on a chime, not on an impact: the same beats play at a third of the punch so the
       * picture does not shout over a narration that has already started. */
      const punch = typeof phase.burstPower === 'number' ? phase.burstPower : 1;
      if (bt >= 0 && bt < 1.05) {
        ctx.save();
        ctx.globalCompositeOperation = 'source-over';
        const flash = Math.max(0, 1 - bt / 0.26);
        if (flash > 0.01) {
          const reach = unit * (0.12 + 0.5 * Math.min(1, bt / 0.26));
          const glow = ctx.createRadialGradient(cx, burstY, 0, cx, burstY, reach);
          glow.addColorStop(0, 'rgba(37, 62, 220, ' + (0.55 * flash * punch).toFixed(3) + ')');
          glow.addColorStop(0.32, 'rgba(77, 107, 254, ' + (0.34 * flash * punch).toFixed(3) + ')');
          glow.addColorStop(1, 'rgba(77, 107, 254, 0)');
          ctx.fillStyle = glow;
          ctx.fillRect(cx - reach, burstY - reach, reach * 2, reach * 2);
        }
        for (let ring = 0; ring < 2; ring++) {
          const k = clampUnit((bt - ring * 0.07) / 0.62);
          if (k <= 0 || k >= 1) continue;
          ctx.beginPath();
          ctx.arc(cx, burstY, unit * 0.5 * Math.pow(k, 0.62), 0, 6.2831853);
          ctx.strokeStyle = 'rgba(77, 107, 254, ' + (0.62 * Math.pow(1 - k, 1.7) * punch).toFixed(3) + ')';
          ctx.lineWidth = 3 * (1 - k) + 0.6;
          ctx.stroke();
        }
        const sparkK = Math.min(1, bt / 0.5);
        for (let i = 0; i < 64; i++) {
          const angle = (i / 64) * 6.2831853 + 0.21;
          const long = 0.05 + 0.2 * (((i * 37) % 100) / 100);
          const inner = unit * 0.04 * Math.pow(sparkK, 0.5);
          const outer = unit * (0.04 + long) * Math.pow(sparkK, 0.55);
          const a = 0.55 * Math.pow(1 - sparkK, 1.4) * punch;
          if (a <= 0.01) continue;
          ctx.beginPath();
          ctx.moveTo(cx + Math.cos(angle) * inner, burstY + Math.sin(angle) * inner);
          ctx.lineTo(cx + Math.cos(angle) * outer, burstY + Math.sin(angle) * outer);
          ctx.strokeStyle = 'rgba(37, 62, 220, ' + a.toFixed(3) + ')';
          ctx.lineWidth = 1.1;
          ctx.stroke();
        }
        ctx.restore();
      }

      const wStart = phase.whale[0];
      const wEnd = phase.whale[1];
      // Form quickly and off-centre, then spend the rest of the beat swimming: that is what makes the mark read as
      // moving, not condensing in place. The pose locks before the salute, so the salute lands on a still mark.
      const lock = Math.min(wEnd, Math.max(wStart + 0.55, Math.min(wStart + 1.02, phase.salute - 0.1)));
      // The whole beat is the crossing, not the second half of it.
      const swim = ramp(wStart + 0.02, lock, t);
      const settle = ramp(lock - 0.12, lock + 0.44, t);
      const size = mix(swimW, restW, settle);
      const fromX = w * 0.19;
      const fromY = h * 0.41;
      const restX = mix(fromX, cx, swim);
      // A shallow arc on the way in: the mark rises out of the burst.
      const restY = mix(fromY, cy, swim) - Math.sin(Math.PI * swim) * unit * 0.05;
      const beat = 6.2831853 * 1.15 * (t - wStart);
      const wagAmp = 0.055 * (1 - settle);
      // No roll: the mark arrives upright and stays upright. The only angle left is the small nose-down tilt of the
      // approach, which is gone by the time the body locks.
      const rot = (1 - swim) * -0.12;
      const cloud = ramp(wStart, wStart + 0.12, t) * (1 - ramp(lock + 0.38, lock + 0.86, t));
      // The mark stays. It fades up out of the dots and holds there for the rest of the cut. Materialising is the
      // slow part: 0.62 s lets the ink bleed through the dots instead of a switch being thrown.
      const ink = ramp(lock - 0.05, lock + 0.62, t);

      if (whale.ready && t >= wStart - 0.02 && cloud > 0.02 && whale.points.length) {
        ctx.save();
        ctx.globalCompositeOperation = 'source-over';
        const points = whale.points;
        const cosR = Math.cos(rot);
        const sinR = Math.sin(rot);
        for (let i = 0; i < points.length; i++) {
          const p = points[i];
          const k = ramp(0, 1, clampUnit((t - (wStart + p.delay * 0.3)) / 0.55));
          if (k <= 0.002) continue;
          // A travelling wave down the body: strong at the tail, still at the head, damped to zero as the mark
          // locks. This is the swim stroke.
          const wag = Math.sin(beat - (p.x + 0.5) * 2.7) * wagAmp * p.tailK;
          const wx = (p.x + wag * 0.35) * size;
          const wy = (p.y + wag) * size;
          const tx = restX + wx * cosR - wy * sinR;
          const ty = restY + wx * sinR + wy * cosR;
          const bx = cx + p.sx * unit * 1.1;
          const by = burstY + p.sy * unit * 1.1;
          const swirl = Math.sin(Math.PI * k) * p.sw * unit * 0.12;
          const px = mix(bx, tx, k) - swirl * p.y * 2.2;
          const py = mix(by, ty, k) + swirl * p.x * 2.2;
          const a = cloud * (0.42 + 0.58 * k) * (0.2 + 0.62 * p.tone);
          const deep = p.tone > 0.72;
          dotAdd(px, py, p.r * (0.7 + 0.5 * k), deep ? Math.min(1, a * 1.1) : a, deep ? 1 : 0);
        }
        dotPaint(['77, 107, 254', '28, 52, 206'], DOT_BUCKETS);
        ctx.restore();
      }

      if (whale.ready) {
        const bloomA = (0.12 + 0.11 * breath) * ramp(wStart, wStart + 0.2, t) * (1 - 0.7 * ink);
        if (bloomA > 0.004) {
          const bloom = ctx.createRadialGradient(cx, cy, 0, cx, cy, ringR * 0.95);
          bloom.addColorStop(0, 'rgba(158, 173, 196, ' + bloomA.toFixed(3) + ')');
          bloom.addColorStop(0.55, 'rgba(186, 200, 222, ' + (bloomA * 0.4).toFixed(3) + ')');
          bloom.addColorStop(1, 'rgba(206, 217, 234, 0)');
          ctx.fillStyle = bloom;
          ctx.fillRect(cx - ringR, cy - ringR, ringR * 2, ringR * 2);
        }
        paintMark(restX, restY, size, ink, rot, t, settle, breath);
      }

      const ringT = t - phase.rings;
      if (ringT > -0.04) {
        const appearAll = ramp(0, 0.3, ringT);
        const defs = [
          { r: ringR, n: 96, spin: 0.26, size: 1.55, a: 0.92 },
          { r: ringR * 0.86, n: 72, spin: -0.34, size: 1.2, a: 0.72 },
          { r: ringR * 0.72, n: 120, spin: 0.44, size: 0.95, a: 0.56 },
        ];
        ctx.save();
        ctx.globalCompositeOperation = 'source-over';
        for (let s = 0; s < defs.length; s++) {
          const def = defs[s];
          const radius = def.r * (1 + 0.014 * breath);
          for (let i = 0; i < def.n; i++) {
            const appear = ramp(0, 1, clampUnit((ringT - (i / def.n) * 0.5) / 0.24));
            if (appear <= 0.01) continue;
            const angle = (i / def.n) * 6.2831853 + def.spin * ringT + 0.35 * s;
            dotAdd(
              cx + Math.cos(angle) * radius,
              cy + Math.sin(angle) * radius,
              def.size * (0.65 + 0.5 * appear),
              def.a * appear * alive,
              0
            );
          }
        }
        const spin = ringT * 1.5;
        const spinR = ringR * (1 + 0.014 * breath);
        for (let i = 0; i < 20; i++) {
          const angle = spin + i * 0.032;
          dotAdd(
            cx + Math.cos(angle) * spinR,
            cy + Math.sin(angle) * spinR,
            Math.max(0.4, 2.3 - i * 0.09),
            (1 - i / 20) * 0.9 * ramp(0, 0.4, ringT),
            1
          );
        }
        dotPaint(['77, 107, 254', '28, 52, 206'], DOT_BUCKETS);
        ctx.restore();

        ctx.globalCompositeOperation = 'source-over';
        for (let s = 0; s < 2; s++) {
          ctx.beginPath();
          ctx.arc(cx, cy, defs[s].r, 0, 6.2831853);
          ctx.strokeStyle = 'rgba(77, 107, 254, ' + (0.16 * alive * appearAll).toFixed(3) + ')';
          ctx.lineWidth = 1;
          ctx.stroke();
        }
      }

      /* The long sequence holds this frame for a dozen seconds while the steps narrate, and a still picture reads as
       * a frozen one. One slow hologram scan crosses the mark every 4.6 s; the short cuts are over before it ever
       * fires. */
      if (phase.salute - phase.burst > 6) {
        const since = t - phase.burst - 2.0;
        if (since > 0) {
          const u = (since % 4.6) / 1.5;
          if (u <= 1) {
            const sx = cx + (u * 1.8 - 0.9) * ringR;
            const top = cy - ringR * 0.82;
            const tall = ringR * 1.64;
            const grad = ctx.createLinearGradient(sx, top, sx, top + tall);
            const a = 0.17 * Math.sin(Math.PI * u);
            grad.addColorStop(0, 'rgba(77, 107, 254, 0)');
            grad.addColorStop(0.5, 'rgba(77, 107, 254, ' + a.toFixed(3) + ')');
            grad.addColorStop(1, 'rgba(77, 107, 254, 0)');
            ctx.fillStyle = grad;
            ctx.fillRect(sx - 1.1, top, 2.2, tall);
          }
        }
      }
    }

    return { resize, frame };
  }

  /**
   * The cut: three beats in one clock, so the picture, the type and the bottom bars stay together.
   * [DSH Mobile] Was the React component `Cyber`. Its element tree is built once with `h`, and its effect runs
   * directly. The clock is the page clock (the original fell back to it when autoplay blocked the audio), scaled to
   * the `seconds` parameter; there is no audio.
   */
  function Cyber(mode, whale) {
    const canvasRef = useRef();
    const centerRef = useRef();
    const leadRef = useRef();
    const nameWrapRef = useRef();
    const nameRef = useRef();
    const ruleRef = useRef();
    const barsRef = useRef();
    const pctRef = useRef();
    const stepsRef = { current: [] };
    const stepsBoxRef = useRef();

    const root = h(
      'div',
      {
        className: 'dshop-root' + (mode.lang === 'zh' ? ' is-zh' : ''),
        style: {
          '--dshop-stage': PALETTE.stage,
          '--dshop-ink': PALETTE.ink,
          '--dshop-dim': PALETTE.dim,
          '--dshop-faint': PALETTE.faint,
          '--dshop-rule': PALETTE.rule,
          '--dshop-accent': PALETTE.accent,
          '--dshop-fade': (mode.cue.fadeOut || 0.28) + 's',
        },
        // [DSH Mobile] the page is the animation, so it is one picture to a screen reader, named by the welcome line
        role: 'img',
        'aria-label': SPOKEN,
      },
      h('canvas', { key: 'field', ref: canvasRef, className: 'dshop-canvas' }),
      mode.cue.steps && mode.cue.steps.length
        ? h(
            'div',
            { key: 'steps', className: 'dshop-cyber-steps', ref: stepsBoxRef },
            mode.cue.steps.map(function (step, i) {
              return h(
                'div',
                {
                  key: i,
                  className: 'dshop-cyber-step',
                  ref: function (el) {
                    stepsRef.current[i] = el;
                  },
                },
                stepTextOf(step, mode.lang)
              );
            })
          )
        : null,
      h(
        'div',
        { className: 'dshop-cyber-center', ref: centerRef },
        h('div', { className: 'dshop-cyber-lead', ref: leadRef }, mode.lines[0]),
        h(
          'div',
          { className: 'dshop-cyber-namewrap', ref: nameWrapRef },
          h('div', { className: 'dshop-cyber-rule', ref: ruleRef }),
          h('h1', { className: 'dshop-cyber-name', ref: nameRef }, mode.lines[1])
        )
      ),
      h(
        'div',
        { className: 'dshop-cyber-bar' },
        h(
          'div',
          { className: 'dshop-cyber-bars', ref: barsRef },
          Array.from({ length: BAR_COUNT }, function (_, i) {
            return h('i', { key: i, className: 'dshop-cyber-tick' });
          })
        ),
        h('div', { className: 'dshop-cyber-pct', ref: pctRef }, '000%')
      )
    );
    document.body.appendChild(root);

    let disposed = false;
    let ending = false;
    let raf = 0;
    const timers = new Set();

    const later = (fn, ms) => {
      const id = setTimeout(() => {
        timers.delete(id);
        if (!disposed) fn();
      }, ms);
      timers.add(id);
    };

    const canvas = canvasRef.current;
    const scene = canvas ? makeCyberScene(canvas, mode.phase, whale) : null;
    if (scene) {
      scene.resize();
      window.addEventListener('resize', scene.resize);
    }

    let startedAt = window.performance.now(); // [DSH Mobile] reset when the first frame is on screen, see below

    const fade = mode.cue.fadeOut || 0.28;
    const total = mode.cue.total || 3.4;
    const span = Math.max(0.6, total - fade);
    /* [DSH Mobile] `seconds` is the whole run, fade included. Longer than the cut: the beats keep their own speed and
     * the landed frame holds (rings turning, bar filling) until the fade. Shorter: every beat plays faster by the same
     * factor. `runSpan` is when the fade starts, in page seconds; `pace` turns page time into cut time. */
    const runSpan = P.seconds ? Math.max(0.6, P.seconds - fade) : span;
    const pace = Math.max(1, span / runSpan);
    const clock = () => ((window.performance.now() - startedAt) / 1000) * pace;

    /* [DSH Mobile] The original faded the overlay off the dsh page underneath and unmounted. Here the end tells the
     * app: the page background clears as the fade starts (so a see-through WebView shows the app through it), and
     * DSHM.done() / #done follow when the fade is over. */
    const stop = (hard) => {
      if (ending || disposed) return;
      ending = true;
      root.classList.add('is-leaving');
      document.documentElement.classList.add('is-leaving');
      later(() => {
        disposed = true;
        if (raf) window.cancelAnimationFrame(raf);
        if (scene) window.removeEventListener('resize', scene.resize);
        window.removeEventListener('resize', measure);
        window.removeEventListener('pointerdown', onSkip, true);
        window.removeEventListener('keydown', onSkip, true);
        if (root.parentNode) root.parentNode.removeChild(root);
        signalDone();
      }, hard ? 170 : (P.reduced ? 200 : fade * 1000) + 40);
    };

    const tickEls = barsRef.current
      ? Array.prototype.slice.call(barsRef.current.children)
      : [];
    const tickAlpha = new Array(tickEls.length).fill(-1);
    const leadEl = leadRef.current;
    const centerEl = centerRef.current;
    const wrapEl = nameWrapRef.current;
    const nameEl = nameRef.current;
    const ruleEl = ruleRef.current;
    const pctEl = pctRef.current;
    let pctText = '';
    let lastProgress = -1;

    /* Assigning a style property dirties the element even when the value did not move, and letterSpacing / width
     * force a re-layout. Most of these values are constant once their ramp has finished, so they are memoised and
     * only pushed when they actually change. */
    const cssMemo = {};
    const setCss = (name, el, prop, value) => {
      const key = name + '.' + prop;
      if (!el || cssMemo[key] === value) return;
      cssMemo[key] = value;
      el.style[prop] = value;
    };

    /* Reading window.innerWidth just after a style write forces a layout flush, and the tick reads it every frame.
     * The viewport only changes on resize, so it is sampled there instead. */
    let vw = window.innerWidth;
    let vh = window.innerHeight;
    let captionY = 0;

    /* [DSH Mobile] A phone is narrower than the desktop window the original was made for, and the name is now the
     * user's (up to 24 characters). A line that would run past 92 % of the screen width at its widest tracking gets
     * a smaller font size, measured once per size. It is never made smaller than `floorPx`: a line that would be (a
     * long welcome on a narrow phone shrank to 8.5 px) goes onto two balanced lines instead (`is-wrapped` in wave.css),
     * at the size that fits two lines, at most its own. */
    const fitLine = (el, trackEm, floorPx) => {
      if (!el || !el.firstChild) return;
      el.style.fontSize = '';
      el.classList.remove('is-wrapped');
      const kept = el.style.letterSpacing;
      el.style.letterSpacing = trackEm + 'em';
      const range = document.createRange();
      range.selectNodeContents(el);
      const style = window.getComputedStyle(el);
      const wide = range.getBoundingClientRect().width + (parseFloat(style.paddingLeft) || 0);
      const room = vw * 0.92;
      const base = parseFloat(style.fontSize) || 16;
      if (wide > room) {
        let size = base * room / wide;
        if (size < floorPx) {
          el.classList.add('is-wrapped');
          size = Math.min(base, base * room * 2 * 0.94 / wide); // 0.94: two lines rarely split exactly in half
        }
        el.style.fontSize = size.toFixed(2) + 'px';
      }
      el.style.letterSpacing = kept;
    };

    /* The caption hangs under the lowest ring (the ring group is centred at 0.44vh with r = min(unit*0.33, 360), same
     * numbers as the canvas), but it may never run into the progress bar. Its own height is read from the laid-out
     * node, so long names push it up instead of being clipped. Recomputed on resize only -- the text does not reflow
     * during the run. */
    const cjkSalute = mode.lang === 'zh' || mode.lang === 'ja';
    const place = () => {
      fitLine(leadEl, mode.leadTrack + 0.2, 12); // [DSH Mobile] floors: 12 px for the small line, 16 px for the big one
      fitLine(nameEl, mode.nameTrack, 16);
      const stage = stageOf(vw, vh); // [DSH Mobile] the canvas's stage: size and height of the rings
      root.classList.toggle('is-compact', stage.compact); // [DSH Mobile] the bar sits lower there (wave.css)
      const unitSpan = stage.unit;
      const ring = Math.min(unitSpan * 0.33, 360);
      /* ~4 mm of daylight under the lowest ring at a normal desktop scale, so the salute never touches the circle. */
      const gap = Math.max(26, unitSpan * 0.07);
      const measured = centerEl ? centerEl.offsetHeight : 0;
      const blockH = measured > 8 ? measured : Math.max(48, unitSpan * 0.1);
      const under = vh * stage.cy + ring + gap;
      const floor = vh * stage.cy + ring * 0.55;
      /* The Chinese and Japanese salutes are tall enough to be clamped upward by the bar; give them a lower bottom
       * line and a small extra drop, so the lead clears the ring instead of hugging it. */
      /* [DSH Mobile] Was `cjkSalute ? 54 : 62`, counted from a bar whose top is 51 px above the bottom edge. The bar's
       * real top is read instead (wave.css puts it lower on short, wide screens), and the Chinese salute keeps 14 px
       * off it instead of 3 px, more than the name's rise-in (9 px and a 7 % zoom): with 3 px, once the bar clamped
       * the caption (a phone held sideways), the name sat on the ticks and ran into them as it rose in. The other
       * value is the original's (62 = 51 + 11). */
      const barsEl = barsRef.current;
      const barTop = barsEl ? barsEl.getBoundingClientRect().top : vh - 51;
      const reserve = vh - barTop + (cjkSalute ? 14 : 11);
      const drop = cjkSalute ? Math.min(unitSpan * 0.02, 22) : 0;
      captionY = Math.min(under + drop, Math.max(floor, vh - reserve - blockH));
      /* The step plate keeps the old anchor; it hands over to a salute that now lands a little lower. */
      const plate = stepsBoxRef.current;
      if (plate) {
        const plateY = Math.min(under, Math.max(floor, vh - 62 - blockH));
        plate.style.transform = 'translateY(' + plateY.toFixed(1) + 'px)';
      }
      // [DSH Mobile] the caption moves at once on a resize, not only on the next progress step
      lastProgress = -1;
    };
    const measure = () => {
      vw = window.innerWidth;
      vh = window.innerHeight;
      place();
    };
    window.addEventListener('resize', measure);
    measure();

    /* [DSH Mobile] The original tick's drawing, split out so the still version can paint one landed frame with it.
     * `flicker` is false for the still frame. */
    const paint = (t, progress, flickers) => {
      if (scene) scene.frame(t);

      if (Math.abs(progress - lastProgress) > 0.0005) {
        lastProgress = progress;

        if (centerEl) {
          setCss('center', centerEl, 'transform', 'translate(-50%, ' + captionY.toFixed(1) + 'px)');
        }

        if (leadEl) {
          const k = ramp(mode.phase.salute, mode.phase.salute + 0.36, t);
          const flicker = flickers ? 0.78 + 0.22 * (0.5 + 0.5 * Math.sin(t * 41.3)) : 1;
          setCss('lead', leadEl, 'opacity', (k * flicker).toFixed(3));
          setCss('lead', leadEl, 'letterSpacing', (mode.leadTrack + 0.2 - 0.2 * k).toFixed(3) + 'em');
        }

        if (wrapEl && nameEl && ruleEl) {
          const k = ramp(mode.phase.name, mode.phase.name + 0.46, t);
          setCss('wrap', wrapEl, 'opacity', k.toFixed(3));
          setCss('name', nameEl, 'transform',
            'translateY(' + ((1 - k) * 9).toFixed(2) + 'px) scale(' + (1.07 - 0.07 * k).toFixed(4) + ')');
          setCss('name', nameEl, 'letterSpacing', (0.07 + (mode.nameTrack - 0.07) * k).toFixed(3) + 'em');
          setCss('name', nameEl, 'textShadow',
            '0 0 ' + (9 + 24 * k).toFixed(1) + 'px rgba(77, 107, 254, ' + (0.12 + 0.32 * k).toFixed(3) + ')');
          setCss('rule', ruleEl, 'width', (k * 190).toFixed(1) + 'px');
          setCss('rule', ruleEl, 'opacity', (k * 0.9).toFixed(3));
        }

        if (tickEls.length) {
          const breather = 0.56 + 0.44 * (0.5 + 0.5 * Math.sin(6.2831853 * 0.32 * (t - 0.9) - 1.1));
          const lit = progress * tickEls.length;
          for (let i = 0; i < tickEls.length; i++) {
            const v = i + 1 <= lit ? 1 : Math.max(0, lit - i);
            const a = 0.1 + 0.9 * v * breather;
            if (Math.abs(a - tickAlpha[i]) > 0.016) {
              tickAlpha[i] = a;
              tickEls[i].style.opacity = a.toFixed(3);
            }
          }
        }

        if (pctEl) {
          const text = ('00' + Math.round(progress * 100)).slice(-3) + '%';
          if (text !== pctText) {
            pctText = text;
            pctEl.textContent = text;
          }
        }
      }

      /* The step plate follows the voice one line at a time: written in as the sentence starts, dissolved as it ends,
       * so the next line arrives into an empty plate. Only one is ever legible. */
      if (stepsRef.current.length) {
        for (let i = 0; i < stepsRef.current.length; i++) {
          const step = mode.cue.steps && mode.cue.steps[i];
          const el = stepsRef.current[i];
          if (!step || !el) continue;
          const enters = ramp(step.at, step.at + 0.22, t);
          const leaves = 1 - ramp(step.end, step.end + 0.22, t);
          const k = enters * leaves;
          setCss('step' + i, el, 'opacity', k.toFixed(3));
          setCss('step' + i, el, 'transform',
            'translateY(' + ((1 - enters) * 10 - (1 - leaves) * 5).toFixed(2) + 'px)');
        }
      }
    };

    const tick = () => {
      if (disposed) return;
      const t = clock();
      // [DSH Mobile] the bar and the end follow the whole run (`runSpan`), which is the cut's own span unless
      // `seconds` asks for a longer hold
      const progress = clampUnit((t / pace) / runSpan);
      paint(t, progress, true);

      if (t / pace >= runSpan) {
        stop(false);
        return;
      }
      raf = window.requestAnimationFrame(tick);
    };

    // [DSH Mobile] a tap or key ends the cut only when the app allows skipping
    const onSkip = () => {
      if (P.skippable) stop(true);
    };
    window.addEventListener('pointerdown', onSkip, true);
    window.addEventListener('keydown', onSkip, true);

    if (P.reduced) {
      /* [DSH Mobile] Reduced motion. The original did not play at all; here one landed frame (mark, rings, words, a
       * full bar) stands still for a moment, then fades in 0.2 s, as the original's reduced-motion rule had it. */
      let still = mode.phase.name + 0.47;
      while (Math.sin(still * 2.3) > 0.9) still += 0.05; // a moment without the mark's occasional stutter
      paint(still, 1, false);
      later(() => stop(false), Math.max(800, Math.min(1500, ((P.seconds || 1.7) - 0.2) * 1000)));
    } else {
      /* [DSH Mobile] The clock starts once the empty stage (the cut's frame at 0 s) is on screen. A WebView's first
       * paint can take a few hundred milliseconds on a phone; with the clock already running, the 0.52 s wave would
       * be mostly over before it was ever seen. */
      window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
          if (disposed || ending) return;
          startedAt = window.performance.now();
          raf = window.requestAnimationFrame(tick);
        });
      });
    }
  }

  /* ---- [DSH Mobile] start: the user's picture (or the stand-in), then the cut ------------------------------------ */

  function start(picture) {
    try {
      if (P.reduced) document.documentElement.classList.add('is-calm');
      const whale = prepareMark(picture, picture ? '' : initialOf(P.name || WORDS));
      Cyber(MODE, whale);
    } catch (error) {
      signalDone();
    }
  }

  // Last resort: whatever happens, the app is told well after the run should have ended.
  setTimeout(signalDone, ((P.seconds || MODE.cue.total) + 6) * 1000);

  /* [DSH Mobile] The round mark is a small portrait, so it shows avatar.img: the app's 512 x 512 square already cut
   * around the face. If that does not load, picture.img (cropped as in prepareMark); if that fails too, the drawn
   * stand-in. The whole chain still gets 1.5 s. */
  function boot() {
    if (!P.picture) {
      start(null);
      return;
    }
    let settled = false;
    const go = (img) => {
      if (settled) return;
      settled = true;
      start(img);
    };
    const load = (src, next) => {
      if (settled) return;
      const img = new Image();
      const ok = () => {
        if (img.naturalWidth > 0 && img.naturalHeight > 0) go(img);
        else next();
      };
      img.onload = () => {
        // decode() keeps the first drawImage of a large picture off the first frames
        if (typeof img.decode === 'function') img.decode().then(ok, ok);
        else ok();
      };
      img.onerror = next;
      img.src = src;
    };
    setTimeout(() => go(null), 1500);
    load('avatar.img', () => load('picture.img', () => go(null)));
  }

  if (document.body) boot();
  else document.addEventListener('DOMContentLoaded', boot);
})();
