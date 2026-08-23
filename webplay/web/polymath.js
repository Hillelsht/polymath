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
  const SITE = 'hillelsht.github.io/polymath';

  /** Grid #1. The first published Chains pack starts here, so the numbering never has a gap. */
  const EPOCH = '2026-08-01';

  const RAW = 'https://raw.githubusercontent.com/Hillelsht/polymath/main/packs/play';

  // --- what language this is ------------------------------------------------------------------
  //
  // The daily exists in three languages because the *content* does: `build_chains.py` builds a
  // grid per language from that language's own library, so a Russian player gets Russian tiles
  // with Russian group labels rather than an English puzzle with translated buttons. That is the
  // whole claim — "playable in your language" — and it is the one thing a wordplay daily
  // structurally cannot copy, because "MOUSE/TRAP/CHEESE" does not survive translation.
  //
  // Which also means the grids are *different puzzles* on the same day, not one puzzle
  // translated. Building one shared grid would mean intersecting three corpora that do not hold
  // the same facts, reducing every language to what the thinnest supports. Sharing still works
  // where sharing happens — between two people playing the same language.

  const LANGUAGES = ['en', 'ru', 'he'];
  const DEFAULT_LANGUAGE = 'en';
  const RTL = new Set(['he']);

  /** Each language named in its own script, never translated — the rule the app's Settings uses. */
  const LANGUAGE_NAMES = { en: 'English', ru: 'Русский', he: 'עברית' };

  // Chains is the one game whose content *is* text, so it exists once per language. A Vaults room
  // is geometry and a clock — there is nothing in it to translate, and pretending otherwise would
  // mean three copies of the same numbers.
  const LOCALISED = { chains: true, vaults: false };

  const LANG_KEY = 'polymath.lang';

  /**
   * The language to play in: what the URL asks for, else what was chosen before, else what the
   * browser is set to. `?lang=` wins so a shared link can carry it and so CI can drive a
   * specific language without touching storage.
   */
  function lang() {
    const asked = new URLSearchParams(location.search).get('lang');
    if (LANGUAGES.includes(asked)) return asked;
    try {
      const saved = localStorage.getItem(LANG_KEY);
      if (LANGUAGES.includes(saved)) return saved;
    } catch { /* private mode; fall through to the browser's own setting */ }
    const preferred = (navigator.languages || [navigator.language || ''])
      .map(tag => String(tag).slice(0, 2).toLowerCase())
      // Hebrew's ISO code changed from `iw` to `he` in 1989 and some platforms still send the old
      // one. The app carries two identical resource folders for the same reason.
      .map(tag => (tag === 'iw' ? 'he' : tag))
      .find(tag => LANGUAGES.includes(tag));
    return preferred || DEFAULT_LANGUAGE;
  }

  /**
   * Switches language and reloads.
   *
   * A reload rather than a re-render: the day's content, every string on the page and the text
   * direction all change together, and half of them are set during a page's own start-up. Doing
   * it by hand would mean every page keeping a second code path that only runs on a switch —
   * exactly the sort of thing that works when written and rots quietly afterwards.
   */
  function setLang(tag) {
    if (!LANGUAGES.includes(tag) || tag === lang()) return;
    try { localStorage.setItem(LANG_KEY, tag); } catch { /* not fatal */ }
    const url = new URL(location.href);
    // Drop `?lang=` rather than rewriting it, so the stored choice is what answers next time.
    // Leaving it would pin the page to one language for every later visit through history.
    url.searchParams.delete('lang');
    location.replace(url.toString());
  }

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

  // The chosen language rather than `undefined`, which asks the *browser* — so a Russian player on
  // an English-locale machine got "MONDAY, NOVEMBER 30" above a Russian grid. The date is the
  // first thing on the page and it was the last thing still speaking English.
  const pretty = (date, language = lang()) =>
    new Date(utc(date)).toLocaleDateString(language, {
      timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long',
    });

  // --- what has been played -------------------------------------------------------------------
  // Every read and write is guarded: Safari in private mode throws on localStorage, and a daily
  // that refuses to start because it cannot remember yesterday would be a poor trade.

  /**
   * Where a day's record lives.
   *
   * Namespaced by language for the games whose content differs by language — which is Chains, and
   * it matters more than it looks. A saved game is a list of *guesses*, replayed through the rules
   * to rebuild the board; the grids differ per language, so replaying an English guess into a
   * Russian grid selects tiles that are not on it. Sharing one key would also merge two different
   * puzzles into one streak.
   *
   * English keeps the unsuffixed key it has always used, so nobody's existing streak resets on the
   * day this ships. That is the same bargain the fact ids and the pack paths make.
   */
  const key = (game, date, language = lang()) =>
    LOCALISED[game] && language !== DEFAULT_LANGUAGE
      ? `polymath.${game}.${language}.${date}`
      : `polymath.${game}.${date}`;

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
   * The day's content for a game: from the copy baked into this build, else from the published pack.
   *
   * The baked-in copy is what makes the page work offline and over `file://` (which is how CI
   * plays it). The fetch is what stops a deploy going stale: packs are refreshed by a bot, and a
   * bot push cannot trigger the workflow that would rebuild this site.
   *
   * Chains calls its days `puzzles` and The Vaults calls them `rooms`, which is worth one `||`
   * here to keep each pack readable as the thing it is when someone opens the file.
   */
  const BAKED = { chains: 'POLYMATH_CHAINS', vaults: 'POLYMATH_VAULTS' };

  /**
   * Loads a language's baked grids on demand.
   *
   * English is baked into `dailies.js`, which every page already carries; the other languages sit
   * in `dailies-<tag>.js` and are only fetched by someone who asked for them. Baking all three
   * into one file would triple what a visitor downloads to pay for two languages they will never
   * see — the grids are a few hundred kilobytes of Wikidata labels, not a few numbers.
   *
   * A `<script>` tag rather than `fetch`, and that is load-bearing: the playtest drives these
   * pages over `file://`, where fetching a sibling file is refused as cross-origin, and a
   * language that only worked over http would be a language CI could never play.
   */
  const scripts = {};
  function baked(game, language) {
    const store = globalThis[BAKED[game]] || {};
    if (!LOCALISED[game]) return Promise.resolve(store);
    if (store[language]) return Promise.resolve(store[language]);
    if (language === DEFAULT_LANGUAGE) return Promise.resolve({});
    if (!scripts[language]) {
      scripts[language] = new Promise(resolve => {
        const tag = document.createElement('script');
        tag.src = `./dailies-${language}.js`;
        tag.onload = () => resolve((globalThis[BAKED[game]] || {})[language] || {});
        // A missing file is not an error: a language may have no grids published yet, and the
        // network fallback below is the same path a month this build predates already takes.
        tag.onerror = () => resolve({});
        document.head.appendChild(tag);
      });
    }
    return scripts[language];
  }

  /** Where a month's pack lives on the CDN. English is the unprefixed path it has always been. */
  function packUrl(game, month, language) {
    const folder = LOCALISED[game] && language !== DEFAULT_LANGUAGE
      ? `${game}/${language}` : game;
    return `${RAW}/${folder}/${month}.json`;
  }

  const months = {};
  async function puzzle(game, date, language = lang()) {
    const month = date.slice(0, 7);
    const key = `${game}/${LOCALISED[game] ? language : '-'}/${month}`;
    if (!months[key]) {
      months[key] = baked(game, language).then(store => store[month] || null)
        .then(pack => pack || fetch(packUrl(game, month, language))
          .then(r => r.ok ? r.json() : null).catch(() => null));
    }
    const pack = await months[key];
    return (pack?.puzzles || pack?.rooms || []).find(p => p.date === date) || null;
  }

  // --- what the page says ---------------------------------------------------------------------
  //
  // Small enough to keep in one table and read as a whole, which is the point: a string file
  // nobody can read end to end is a string file with an untranslated line in it. Marked up in the
  // pages as `data-t="key"`, filled in by `dress()`.
  //
  // Each language is written in its own voice rather than translated word for word. Russian
  // "Цепочки" is what the game is called, not a rendering of "Chains".

  const STRINGS = {
    en: {
      'nav.chains': 'Chains',
      'nav.vaults': 'The Vaults',
      'lang.label': 'Language',
      'no': 'no.',
      'home.title': 'Something new, every day',
      'home.chains': 'Sixteen things, four hidden groups, four mistakes. The same grid everybody else got.',
      'home.vaults': 'One room under one clock. Blades on cycles, stone that will not hold you twice, and a time worth beating.',
      'home.unplayed': 'Not played yet',
      'home.unrun': 'Not run yet',
      'home.solved': 'Solved today',
      'home.solvedPerfect': 'Solved today — perfect',
      'home.noLuck': 'Finished today — no luck',
      'home.inProgress': 'In progress',
      'home.outIn': 'Out in {s}s today',
      'home.oneDay': 'One day down. Come back tomorrow and it becomes a streak.',
      'home.inARow': '{n} days in a row. Grid {no}.',
      'chains.lede': 'Sixteen things, four hidden groups of four. <b>Four mistakes</b> — and a guess with three of a group right is told so, because otherwise being wrong teaches you nothing.',
      'chains.shuffle': 'Shuffle',
      'chains.deselect': 'Deselect',
      'chains.submit': 'Submit',
      'chains.share': 'Share result',
      'chains.lives': 'Mistakes remaining',
      'chains.perfect': 'Perfect.',
      'chains.solved': 'Solved.',
      'chains.lost': 'Out of guesses.',
      'chains.oneAway': 'One away…',
      'chains.notAGroup': 'Not a group.',
      'chains.missing': 'There is no grid for this day yet. The packs are generated a month at a time.',
      'chains.rejected': "Today's grid did not pass its own checks, so it is not being served.",
      'chains.copied': 'Copied — paste it anywhere.',
      'chains.copyFailed': 'Could not copy it; the grid above can be selected by hand.',
      'chains.livesLeft': '{n} of {m} lives left',
      'chains.streak': 'streak {n}',
      'chains.tomorrow': 'back tomorrow.',
      'share.perfect': 'perfect',
      'share.beatMe': 'beat me',
      'chains.foot': 'The grid is the same one everybody else got today, in this language: tile order is published with the puzzle rather than shuffled on your device, so a shared result describes a board your friend will recognise. Shuffling here only rearranges what is on your screen.',
    },
    ru: {
      'nav.chains': 'Цепочки',
      'nav.vaults': 'Подземелье',
      'lang.label': 'Язык',
      'no': '№',
      'home.title': 'Каждый день — что-то новое',
      'home.chains': 'Шестнадцать слов, четыре скрытые группы, четыре ошибки. Та же сетка, что и у всех.',
      'home.vaults': 'Один зал, одни часы. Лезвия по циклу, камень, который не выдержит дважды, и время, которое стоит побить.',
      'home.unplayed': 'Ещё не сыграно',
      'home.unrun': 'Ещё не пройдено',
      'home.solved': 'Сегодня решено',
      'home.solvedPerfect': 'Сегодня решено — без ошибок',
      'home.noLuck': 'Сегодня не вышло',
      'home.inProgress': 'В процессе',
      'home.outIn': 'Сегодня пройдено за {s} с',
      'home.oneDay': 'Первый день позади. Зайдите завтра — и будет серия.',
      'home.inARow': 'Дней подряд: {n}. Сетка {no}.',
      'chains.lede': 'Шестнадцать слов, четыре скрытые группы по четыре. <b>Четыре ошибки</b> — а если в догадке угадано трое из группы, вам об этом скажут: иначе ошибка ничему не учит.',
      'chains.shuffle': 'Перемешать',
      'chains.deselect': 'Снять выбор',
      'chains.submit': 'Проверить',
      'chains.share': 'Поделиться',
      'chains.lives': 'Осталось ошибок',
      'chains.perfect': 'Безупречно.',
      'chains.solved': 'Решено.',
      'chains.lost': 'Попытки закончились.',
      'chains.oneAway': 'Почти…',
      'chains.notAGroup': 'Не группа.',
      'chains.missing': 'На этот день сетки пока нет. Наборы готовятся помесячно.',
      'chains.rejected': 'Сегодняшняя сетка не прошла собственные проверки, поэтому она не выдаётся.',
      'chains.copied': 'Скопировано — вставьте куда угодно.',
      'chains.copyFailed': 'Скопировать не удалось; сетку выше можно выделить вручную.',
      'chains.livesLeft': 'осталось жизней: {n} из {m}',
      'chains.streak': 'серия {n}',
      'chains.tomorrow': 'до завтра.',
      'share.perfect': 'без ошибок',
      'share.beatMe': 'попробуй лучше',
      'chains.foot': 'Эта сетка сегодня одна и та же у всех, кто играет на этом языке: порядок плиток публикуется вместе с головоломкой, а не перемешивается на вашем устройстве, поэтому в присланном результате друг узнает своё поле. Кнопка «Перемешать» меняет только то, что на вашем экране.',
    },
    he: {
      'nav.chains': 'שרשראות',
      'nav.vaults': 'המרתפים',
      'lang.label': 'שפה',
      'no': 'מס׳',
      'home.title': 'משהו חדש, כל יום',
      'home.chains': 'שישה־עשר דברים, ארבע קבוצות נסתרות, ארבע טעויות. אותה משבצת שכולם קיבלו.',
      'home.vaults': 'חדר אחד תחת שעון אחד. להבים במחזורים, אבן שלא תחזיק אתכם פעמיים, וזמן ששווה לשבור.',
      'home.unplayed': 'טרם שוחק',
      'home.unrun': 'טרם רוצה',
      'home.solved': 'נפתר היום',
      'home.solvedPerfect': 'נפתר היום — ללא טעויות',
      'home.noLuck': 'הסתיים היום — לא הצליח',
      'home.inProgress': 'בעיצומו',
      'home.outIn': 'יצאתם היום ב־{s} שניות',
      'home.oneDay': 'יום אחד מאחורינו. חזרו מחר וזה יהפוך לרצף.',
      'home.inARow': '{n} ימים ברצף. משבצת {no}.',
      'chains.lede': 'שישה־עשר דברים, ארבע קבוצות נסתרות של ארבעה. <b>ארבע טעויות</b> — וניחוש שבו שלושה מתוך קבוצה נכונים יקבל על כך הודעה, אחרת אין מה ללמוד מטעות.',
      'chains.shuffle': 'ערבב',
      'chains.deselect': 'בטל בחירה',
      'chains.submit': 'בדוק',
      'chains.share': 'שתף תוצאה',
      'chains.lives': 'טעויות שנותרו',
      'chains.perfect': 'מושלם.',
      'chains.solved': 'נפתר.',
      'chains.lost': 'נגמרו הניחושים.',
      'chains.oneAway': 'כמעט…',
      'chains.notAGroup': 'לא קבוצה.',
      'chains.missing': 'אין עדיין משבצת ליום הזה. החבילות נוצרות חודש בכל פעם.',
      'chains.rejected': 'המשבצת של היום לא עברה את הבדיקות שלה, ולכן היא אינה מוגשת.',
      'chains.copied': 'הועתק — אפשר להדביק בכל מקום.',
      'chains.copyFailed': 'לא הצלחנו להעתיק; אפשר לסמן את המשבצת שלמעלה ידנית.',
      'chains.livesLeft': 'נותרו {n} מתוך {m} חיים',
      'chains.streak': 'רצף {n}',
      'chains.tomorrow': 'נתראה מחר.',
      'share.perfect': 'ללא טעויות',
      'share.beatMe': 'נסו לנצח',
      'chains.foot': 'המשבצת היום זהה לכל מי שמשחק בשפה הזאת: סדר האריחים מתפרסם יחד עם החידה ולא מעורבב במכשיר שלכם, כך שתוצאה משותפת מתארת לוח שחבר שלכם יזהה. ״ערבב״ משנה רק את מה שעל המסך שלכם.',
    },
  };

  /**
   * The number of guesses, in a language's own grammar.
   *
   * Not a formatting nicety. Russian takes three forms by the last digit — одна попытка, две
   * попытки, пять попыток — and getting it wrong is the single most obvious way a translated
   * interface announces that nobody who speaks the language read it. Hebrew takes two.
   */
  function guesses(n) {
    switch (lang()) {
      case 'ru': {
        const ten = n % 10, hundred = n % 100;
        if (ten === 1 && hundred !== 11) return `${n} попытка`;
        if (ten >= 2 && ten <= 4 && (hundred < 12 || hundred > 14)) return `${n} попытки`;
        return `${n} попыток`;
      }
      case 'he':
        return n === 1 ? 'ניחוש אחד' : `${n} ניחושים`;
      default:
        return `${n} guess${n === 1 ? '' : 'es'}`;
    }
  }

  /** One string, with `{name}` placeholders filled in. Falls back to English, then to the key. */
  function t(key, values) {
    const table = STRINGS[lang()] || STRINGS[DEFAULT_LANGUAGE];
    const text = table[key] ?? STRINGS[DEFAULT_LANGUAGE][key] ?? key;
    return values
      ? text.replace(/\{(\w+)}/g, (whole, name) => (name in values ? values[name] : whole))
      : text;
  }

  /**
   * Puts the page into the current language: direction, `lang` attribute, every `data-t` element,
   * and the switcher itself.
   *
   * `dir` on the root element is the whole of right-to-left support here, exactly as it is in the
   * app: the browser mirrors the layout, and a grid laid out with flex and grid needs no separate
   * Hebrew stylesheet. `data-t-html` exists for the two strings carrying a `<b>`.
   */
  function dress(root = document) {
    const tag = lang();
    document.documentElement.lang = tag;
    document.documentElement.dir = RTL.has(tag) ? 'rtl' : 'ltr';
    // The tab title is not an element the sweep below can reach, and it is what a player sees in
    // a list of twenty open tabs.
    const titled = document.querySelector('[data-t-title]');
    if (titled) document.title = `${t(titled.dataset.tTitle)} — Polymath`;
    root.querySelectorAll('[data-t]').forEach(node => {
      node.textContent = t(node.dataset.t);
    });
    root.querySelectorAll('[data-t-html]').forEach(node => {
      node.innerHTML = t(node.dataset.tHtml);
    });
    root.querySelectorAll('[data-t-label]').forEach(node => {
      node.setAttribute('aria-label', t(node.dataset.tLabel));
    });
    root.querySelectorAll('[data-lang-switch]').forEach(mount => switcher(mount));
  }

  /** The switcher: three buttons, each naming its own language in its own script. */
  function switcher(mount) {
    const current = lang();
    mount.replaceChildren(...LANGUAGES.map(tag => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'pm-lang' + (tag === current ? ' on' : '');
      b.textContent = LANGUAGE_NAMES[tag];
      b.lang = tag;
      b.setAttribute('aria-pressed', String(tag === current));
      b.addEventListener('click', () => setLang(tag));
      return b;
    }));
    mount.setAttribute('role', 'group');
    mount.setAttribute('aria-label', t('lang.label'));
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

  // --- the only measurement, and it is off ----------------------------------------------------

  /**
   * A privacy-friendly page counter, disabled until someone fills this in.
   *
   * Wedge 1 asks for exactly one measurement — enough to see whether the share loop works — and
   * nothing else. This is the whole of it: a Plausible-class counter takes no cookies, builds no
   * profile and needs no consent banner, and turning it on is setting two strings.
   *
   * It ships off rather than on because enabling it needs an account this repository does not
   * have, and because switching it on is a decision about someone else's data, not a default.
   * Until then this site makes no third-party request at all.
   *
   * If you do enable it: the footers on `index.html` and `descent.html` currently tell visitors
   * there is no server keeping any of this. Make that stay true, or change the words.
   */
  const COUNTER = { host: '', domain: '' };   // e.g. { host: 'plausible.io', domain: 'polymath.games' }

  function count() {
    if (!COUNTER.host || !COUNTER.domain) return false;
    const tag = document.createElement('script');
    tag.defer = true;
    tag.src = `https://${COUNTER.host}/js/script.js`;
    tag.setAttribute('data-domain', COUNTER.domain);
    document.head.appendChild(tag);
    return true;
  }

  return {
    SITE, EPOCH, today, dayNumber, shift, pretty, load, save, streak, puzzle, copy, share,
    COUNTER, count,
    LANGUAGES, DEFAULT_LANGUAGE, LANGUAGE_NAMES, lang, setLang, t, guesses, dress,
  };
})();

PM.count();

globalThis.PM = PM;
