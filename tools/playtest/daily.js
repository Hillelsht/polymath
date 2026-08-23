#!/usr/bin/env node
/**
 * Plays the daily through its own interface, in a real browser.
 *
 * Same lesson as the Vaults playtest, applied one layer up. `ChainsRules` is thoroughly tested and
 * every published grid is validated by `enginetests`, and none of that would notice a Submit
 * button that stays disabled, a board that forgets itself on refresh, or a share grid with the
 * rows in the wrong order. Those are the things a daily actually is, so they get clicked.
 *
 * What it asserts:
 *   1. the grid paints, sixteen tiles, out of the copy baked into the build
 *   2. a right guess takes a group off the board and a wrong one costs a life
 *   3. a part-played grid survives a reload — the streak's whole premise
 *   4. finishing produces a share grid of one row per guess, coloured by the true groups
 *   5. two days finished in a row read as a streak of two, on the front door as well
 *
 * Usage:  node tools/playtest/daily.js [--date YYYY-MM-DD] [--shot out.png] [--headed]
 *
 * Requires `gradle -p webplay bundle` first. Run with
 *   NODE_PATH=/opt/node22/lib/node_modules node tools/playtest/daily.js
 * if playwright is not resolvable from the working directory.
 */
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const { serve } = require('./serve');

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const ROOT = path.resolve(__dirname, '..', '..');
const SITE = path.join(ROOT, 'webplay', 'build', 'web');
const SHOT = arg('--shot', path.join(SITE, 'daily.png'));

const CHROME = ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
                '/opt/pw-browsers/chromium/chrome-linux/chrome']
  .find(p => fs.existsSync(p));

const SQUARE = /^[\u{1F7E6}\u{1F7E8}\u{1F7E9}\u{1F7EA}\u{2B1B}]+$/u;

/**
 * The grids a language's baked file actually contains, read the way the browser reads them.
 *
 * By evaluating the file rather than slicing between its braces. The file is a script, not JSON —
 * it merges itself into a shared global so three languages can arrive in any order — and the old
 * `js.slice(indexOf('{'), lastIndexOf('}'))` broke the moment that shape changed. Running it is
 * both simpler and exactly what the page does.
 */
function bakedPacks(language) {
  const file = language === 'en' ? 'dailies.js' : `dailies-${language}.js`;
  const full = path.join(SITE, file);
  if (!fs.existsSync(full)) return null;
  const world = {};
  new Function('globalThis', fs.readFileSync(full, 'utf8'))(world);
  return (world.POLYMATH_CHAINS || {})[language] || null;
}

/** The last day this build was given a grid for, so the run never depends on the wall clock. */
function lastBakedDate(language = 'en') {
  const packs = bakedPacks(language);
  if (!packs) return null;
  const dates = Object.values(packs).flatMap(p => p.puzzles.map(q => q.date)).sort();
  return dates[dates.length - 1] || null;
}

const problems = [];
const check = (ok, what) => {
  console.log(`  ${ok ? '·' : '✗'} ${what}`);
  if (!ok) problems.push(what);
};

/**
 * The day's four groups, read from the data the page was built with.
 *
 * Deliberately not read out of the session: the bridge does not hand out the answer to a grid in
 * progress, and it should not. This checks the interface around the rules, not whether it can
 * solve the puzzle.
 */
const answerFor = (page, date, language = 'en') => page.evaluate(([d, lang]) => {
  const pack = (globalThis.POLYMATH_CHAINS[lang] || {})[d.slice(0, 7)];
  return pack ? pack.puzzles.find(p => p.date === d).groups.map(g => g.members) : null;
}, [date, language]);

(async () => {
  if (!fs.existsSync(path.join(SITE, 'index.html'))) {
    console.error('No site. Run: gradle -p webplay bundle');
    process.exit(2);
  }

  const DATE = arg('--date', lastBakedDate());
  const YESTERDAY = new Date(Date.parse(DATE + 'T00:00:00Z') - 86400000).toISOString().slice(0, 10);

  const host = await serve(SITE);
  const browser = await chromium.launch({ executablePath: CHROME, headless: !argv.includes('--headed') });
  const context = await browser.newContext({ viewport: { width: 900, height: 1100 } });
  const page = await context.newPage();

  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  const open = async (where, date, language) => {
    const lang = language ? `&lang=${language}` : '';
    await page.goto(`${host.url}/${where}?date=${date}${lang}`);
    await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  };
  // Anchored, because `hasText` matches substrings and one tile's label can sit inside another's.
  const tap = name =>
    page.locator('.pm-tile', { hasText: new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) })
      .first().click();
  const solve = async group => {
    for (const tile of group) await tap(tile);
    await page.locator('#c-submit').click();
  };

  console.log(`  daily for ${DATE}`);
  await open('chains.html', DATE);

  // 1 — it painted, with no network fetch behind it
  check(await page.locator('.pm-tile').count() === 16, 'sixteen tiles on the board');
  check(await page.locator('#c-play').isVisible(), 'the board is showing');
  check(await page.locator('#c-submit').isDisabled(), 'Submit is refused until four are picked');
  // An empty result panel sitting above an unplayed grid is what a `hidden` attribute looks like
  // when an author `display` rule outranks it, which is easy to write and invisible in a unit test.
  check(await page.locator('#c-result').isHidden(), 'and there is no result panel yet');

  const answer = await answerFor(page, DATE);
  check(answer?.length === 4 && answer.every(g => g.length === 4),
        'the grid describes four groups of four');

  // 2 — two halves of two different groups cannot be a group, and saying so costs a life
  for (const tile of [answer[0][0], answer[0][1], answer[1][0], answer[1][1]]) await tap(tile);
  check(await page.locator('#c-submit').isEnabled(), 'Submit opens once four are picked');
  await page.locator('#c-submit').click();
  check(await page.locator('.pm-life.spent').count() === 1, 'a wrong guess costs exactly one life');
  check(await page.locator('.pm-tile').count() === 16, 'and leaves the board alone');

  await page.locator('#c-clear').click();
  await solve(answer[0]);
  check(await page.locator('.pm-group').count() === 1, 'a solved group moves above the board');
  check(await page.locator('.pm-tile').count() === 12, 'and its tiles leave the grid');

  // 3 — a part-played grid survives a reload. Without this the daily is unplayable on a phone,
  //     where a switched tab is a reload.
  await open('chains.html', DATE);
  check(await page.locator('.pm-group').count() === 1, 'the solved group survives a reload');
  check(await page.locator('.pm-tile').count() === 12, 'and so does the shrunken board');
  check(await page.locator('.pm-life.spent').count() === 1, 'and the life it cost is still spent');

  // 4 — finish it, and check the share grid describes what actually happened
  for (const group of answer.slice(1)) await solve(group);
  check(await page.locator('#c-result').isVisible(), 'finishing shows the result');
  check((await page.locator('#c-verdict').textContent()).includes('Solved'), 'and calls it solved');

  const share = await page.evaluate(() => window.__shareText());
  const rows = share.split('\n').filter(line => SQUARE.test(line));
  check(rows.length === 5, `the share grid has one row per guess (saw ${rows.length} of 5)`);
  check(rows.every(r => [...r].length === 4), 'every row is four squares wide');
  check(rows.slice(1).every(r => new Set([...r]).size === 1), 'each solved group is one colour');
  check(new Set([...rows[0]]).size === 2, 'and the wrong guess shows as two');
  check(share.includes('Polymath') && share.includes('Chains'), 'the share names the game');

  // 5 — two days running is a streak of two
  const yesterday = await (async () => {
    await open('chains.html', YESTERDAY);
    return answerFor(page, YESTERDAY);
  })();
  if (yesterday) {
    for (const group of yesterday) await solve(group);
    await open('chains.html', DATE);
    const said = (await page.locator('#c-streak').textContent()).trim();
    check(/streak 2/.test(said), `two days running reads as a streak of two (said "${said}")`);

    await open('index.html', DATE);
    check(/Solved today/.test(await page.locator('#p-chains').textContent()),
          'and the front door knows today is done');
  } else {
    console.log(`  · no grid baked in for ${YESTERDAY}; skipping the streak check`);
  }

  // --- the same daily, in the other two languages ---------------------------------------------
  //
  // The claim the whole wedge rests on is "playable in your language", and the way it fails is
  // quiet: a translated build that serves English tiles under Russian buttons looks localised in a
  // screenshot and is not. So this plays a real Russian grid through the page's own controls and
  // checks the three things that could each be true on their own while the feature is broken —
  // that the content is Russian, that the chrome is Russian, and that it is a *different* puzzle
  // from the English one rather than the same grid relabelled.

  for (const [tag, sample, submit] of [['ru', /[А-Яа-яЁё]/, 'Проверить'],
                                       ['he', /[\u0590-\u05FF]/, 'בדוק']]) {
    const day = lastBakedDate(tag);
    if (!day) {
      console.log(`  · nothing baked for ${tag}; skipping`);
      continue;
    }
    // A fresh context, because a language's saved games are its own and a half-played English
    // grid in storage must not be what makes this pass.
    const other = await context.newPage();
    other.on('pageerror', e => errors.push(`[${tag}] ${e}`));
    await other.goto(`${host.url}/chains.html?date=${day}&lang=${tag}`);
    await other.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });

    check(await other.locator('.pm-tile').count() === 16, `[${tag}] sixteen tiles`);
    const tiles = await other.locator('.pm-tile').allTextContents();
    check(tiles.some(t => sample.test(t)), `[${tag}] the tiles are in the language asked for`);
    check((await other.locator('#c-submit').textContent()).trim() === submit,
          `[${tag}] and so is the chrome`);
    check(await other.locator(`.pm-lang.on`).textContent() ===
          { ru: 'Русский', he: 'עברית' }[tag],
          `[${tag}] the switcher shows which language is on`);
    check(await other.evaluate(() => document.documentElement.lang) === tag,
          `[${tag}] the document declares its language`);
    check(await other.evaluate(() => document.documentElement.dir) ===
          (tag === 'he' ? 'rtl' : 'ltr'),
          `[${tag}] and its direction`);

    const english = await answerFor(other, day, 'en');
    const mine = await answerFor(other, day, tag);
    check(mine?.length === 4 && mine.every(g => g.length === 4),
          `[${tag}] the grid describes four groups of four`);
    check(english === null || JSON.stringify(mine) !== JSON.stringify(english),
          `[${tag}] and it is its own puzzle, not the English one relabelled`);

    // It has to be playable, not merely legible.
    for (const tile of mine[0]) {
      await other.locator('.pm-tile', {
        hasText: new RegExp(`^${tile.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
      }).first().click();
    }
    await other.locator('#c-submit').click();
    check(await other.locator('#c-groups .pm-group').count() === 1,
          `[${tag}] a correct group solves`);
    check(await other.locator('.pm-life.spent').count() === 0,
          `[${tag}] and costs no life`);

    await other.screenshot({ path: SHOT.replace(/\.png$/, `-${tag}.png`), fullPage: true });
    await other.close();
  }

  await open('chains.html', DATE);
  await page.screenshot({ path: SHOT, fullPage: true });
  console.log(`  screenshot: ${SHOT}`);

  await browser.close();
  await host.close();

  if (errors.length) {
    console.error('Page errors:\n  ' + errors.join('\n  '));
    process.exit(1);
  }
  if (problems.length) {
    console.error(`\n${problems.length} failure(s):\n  ` + problems.join('\n  '));
    process.exit(1);
  }
  console.log('  the daily plays');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
