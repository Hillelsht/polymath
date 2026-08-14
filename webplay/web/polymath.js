/*
 * Shared portal plumbing: what day it is, what has been played, and how to hand a result on.
 *
 * There is no backend and there is not going to be one for a while — that is a deliberate bet
 * recorded in plan.md, and the cost structure is part of the product. So a "day" is the player's
 * own local day, a streak is derived from what is in localStorage rather than from an account,
 * and a shared result is a piece of text.
 */
const PM = (() => {

  /** Where a shared result points. One constant, so a real domain is a one-line change. */
  const SITE = 'hillelsht.github.io/smart';

  /** Grid #1. The first published Chains pack starts here, so the numbering never has a gap. */
  const EPOCH = '2026-08-01';

  const RAW = 'https://raw.githubusercontent.com/Hillelsht/smart/main/packs/play';

  const pad = n => String(n).padStart(2, '0');
  const iso = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const utc = s => Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10));

  /**
   * Today, in the player's own timezone.
   *
   * Local rather than UTC because a daily that rolls over at some other continent's midnight is a
   * daily that arrives during your afternoon. `?date=` overrides it, which is how CI drives a
   * specific grid and how a future archive will link to one.
   */
  function today() {
    const asked = new URLSearchParams(location.search).get('date');
    return /^\d{4}-\d{2}-\d{2}$/.test(asked || '') ? asked : iso(new Date());
  }

  const dayNumber = date => Math.round((utc(date) - utc(EPOCH)) / 86400000) + 1;

  // Calendar arithmetic stays entirely in UTC space and is read back with UTC getters. Formatting
  // the result with local getters instead would land on the previous day west of Greenwich, which
  // is the sort of bug that only shows up for half the world.
  const shift = (date, days) => {
    const d = new Date(utc(date) + days * 86400000);
    return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
  };

  const pretty = date => new Date(utc(date)).toLocaleDateString(undefined, {
    timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long',
  });

  // --- what has been played -------------------------------------------------------------------
  // Every read and write is guarded: Safari in private mode throws on localStorage, and a daily
  // that refuses to start because it cannot remember yesterday would be a poor trade.

  const key = (game, date) => `polymath.${game}.${date}`;

  function load(game, date) {
    try { return JSON.parse(localStorage.getItem(key(game, date))) || null; } catch { return null; }
  }

  function save(game, date, record) {
    try { localStorage.setItem(key(game, date), JSON.stringify(record)); } catch { /* not fatal */ }
  }

  /**
   * Days won in an unbroken run ending today.
   *
   * Counted backwards from today's record, or from yesterday's when today has not been finished —
   * otherwise picking up the puzzle in the morning would appear to have wiped the streak.
   */
  function streak(game, date) {
    let day = load(game, date)?.won ? date : shift(date, -1);
    let n = 0;
    while (load(game, day)?.won) { n++; day = shift(day, -1); }
    return n;
  }

  // --- the day's content ------------------------------------------------------------------------

  /**
   * The grid for a date: from the copy baked into this build, else from the published pack.
   *
   * The baked-in copy is what makes the page work offline and over `file://` (which is how CI
   * plays it). The fetch is what stops a deploy going stale: packs are refreshed by a bot, and a
   * bot push cannot trigger the workflow that would rebuild this site.
   */
  const months = {};
  async function puzzle(game, date) {
    const month = date.slice(0, 7);
    if (!months[month]) {
      const baked = (globalThis.POLYMATH_CHAINS || {})[month];
      months[month] = baked ? Promise.resolve(baked)
        : fetch(`${RAW}/${game}/${month}.json`).then(r => r.ok ? r.json() : null).catch(() => null);
    }
    const pack = await months[month];
    return pack?.puzzles?.find(p => p.date === date) || null;
  }

  // --- handing a result on ------------------------------------------------------------------------

  /**
   * Copies text, reporting whether it landed.
   *
   * The async clipboard API is unavailable outside a secure context — which includes the
   * `file://` page CI drives — so the old selection trick stays as the fallback rather than the
   * share button simply doing nothing there.
   */
  async function copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch { /* fall through */ }
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.cssText = 'position:fixed;top:-1000px;opacity:0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch { return false; }
  }

  /** The system share sheet where there is one, falling back to the clipboard where there is not. */
  async function share(text) {
    if (navigator.share) {
      try { await navigator.share({ text }); return 'shared'; } catch (e) {
        if (e && e.name === 'AbortError') return 'cancelled';
      }
    }
    return await copy(text) ? 'copied' : 'failed';
  }

  return { SITE, EPOCH, today, dayNumber, shift, pretty, load, save, streak, puzzle, copy, share };
})();

globalThis.PM = PM;
