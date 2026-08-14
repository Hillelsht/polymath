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

/** The last day this build was given a grid for, so the run never depends on the wall clock. */
function lastBakedDate() {
  const js = fs.readFileSync(path.join(SITE, 'dailies.js'), 'utf8');
  const packs = JSON.parse(js.slice(js.indexOf('{'), js.lastIndexOf('}') + 1));
  const dates = Object.values(packs).flatMap(p => p.puzzles.map(q => q.date)).sort();
  return dates[dates.length - 1];
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
const answerFor = (page, date) => page.evaluate(d => {
  const pack = globalThis.POLYMATH_CHAINS[d.slice(0, 7)];
  return pack ? pack.puzzles.find(p => p.date === d).groups.map(g => g.members) : null;
}, date);

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

  const open = async (where, date) => {
    await page.goto(`${host.url}/${where}?date=${date}`);
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
