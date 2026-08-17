#!/usr/bin/env node
/**
 * Races a ghost through a real browser, which is the only way to know the link works.
 *
 * `GhostTest` proves the format round-trips and that a replayed run matches the one it came from,
 * on the JVM. That is necessary and it is not the claim being made to a player, which is: *paste
 * this link to a friend and they see the attempt you had.* Between those two sits a URL fragment,
 * a page load, a decoder and a render loop — none of which the engine tests touch.
 *
 * So this plays today's room for real, takes the link the page offers, opens that link in a fresh
 * page, and checks the ghost that comes back finishes in exactly the same number of frames.
 *
 * Usage:  node tools/playtest/ghost.js [--shot out.png] [--headed]
 *
 * Requires `gradle -p webplay bundle` first. Run with
 *   NODE_PATH=/opt/node22/lib/node_modules node tools/playtest/ghost.js
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
const SHOT = arg('--shot', path.join(SITE, 'ghost.png'));

const CHROME = ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
                '/opt/pw-browsers/chromium/chrome-linux/chrome']
  .find(p => fs.existsSync(p));

const problems = [];
const check = (ok, what) => {
  console.log(`  ${ok ? '·' : '✗'} ${what}`);
  if (!ok) problems.push(what);
};

/**
 * Drives the page's own session to clear the room, the way the margin solver does: hold right,
 * press jump on one frame, search for a frame that works. Deliberately not a recorded input list —
 * the room changes daily, so the test has to solve whatever it is given.
 */
const clearTheRoom = page => page.evaluate(() => {
  const s = window.__vaults();
  for (let wait = 0; wait < 140; wait += 2) {
    for (let press = 0; press < 300; press++) {
      s.restart();
      for (let f = 0; f < 480; f++) {
        s.tick(false, f >= wait, f === wait + press, false);
        if (s.finished) return { frames: s.elapsedFrames, deaths: s.deaths, ghost: s.recording() };
        if (s.phase === 'DEAD') break;
      }
    }
  }
  return null;
});

(async () => {
  if (!fs.existsSync(path.join(SITE, 'descent.html'))) {
    console.error('No site. Run: gradle -p webplay bundle');
    process.exit(2);
  }

  const host = await serve(SITE);
  const browser = await chromium.launch({ executablePath: CHROME, headless: !argv.includes('--headed') });
  const context = await browser.newContext({ viewport: { width: 1000, height: 900 } });
  const page = await context.newPage();

  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  // Cleared first, deliberately: going straight from one fragment to another is a same-document
  // navigation, so the page would keep running and `__ready` would still be true from last time.
  // The page reloads itself on `hashchange` for real visitors; the test must not lean on that.
  const open = async hash => {
    await page.goto('about:blank');
    await page.goto(`${host.url}/descent.html${hash || ''}`);
    await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  };

  await open();
  check(await page.locator('#c').isVisible(), 'the room is on screen');
  check(await page.locator('#v-result').isHidden(), 'and there is no result yet');
  const room = (await page.locator('#v-room').textContent()).trim();
  console.log(`  today's room: ${room}`);

  // 1 — clear it, and take the link the page offers
  const run = await clearTheRoom(page);
  check(run !== null, 'the room can be cleared');
  if (!run) { await browser.close(); await host.close(); process.exit(1); }
  console.log(`  cleared in ${(run.frames / 60).toFixed(1)}s, ${run.deaths} deaths`);
  check(run.ghost.length > 0, 'the run records to a ghost');
  check(run.ghost.length < 400, `and the ghost is short enough to paste (${run.ghost.length} chars)`);

  // 2 — the link a player would actually send
  const share = await page.evaluate(g => {
    const s = window.__vaults();
    // Reproduce the page's own share, which reads the finished run off the button's dataset.
    const b = document.getElementById('v-share').dataset;
    b.ghost = g.ghost; b.frames = g.frames; b.deaths = g.deaths;
    return window.__shareText();
  }, run);
  check(share.includes('Polymath') && share.includes('The Vaults'), 'the share names the game');
  const link = (share.match(/#d=[\d-]+&g=\S+/) || [])[0];
  check(!!link, 'the share carries a ghost link');
  if (!link) { await browser.close(); await host.close(); process.exit(1); }

  // 3 — open the link the way the friend would, and race it
  await open(link);
  check(await page.locator('#v-ghost').isVisible(), 'opening the link announces a ghost');
  const raced = await page.evaluate(() => {
    const s = window.__vaults();
    if (!s.hasGhost) return null;
    for (let f = 0; f < 3000 && !s.ghostFinished && !s.ghostSpent; f++) s.tick(false, false, false, false);
    return { finished: s.ghostFinished, frames: s.ghostElapsedFrames };
  });
  check(raced !== null, 'the link decodes into a ghost');
  check(raced && raced.finished, 'the ghost finishes the room');
  check(
    raced && raced.frames === run.frames,
    `the ghost takes exactly as long as the run did (${raced && raced.frames} vs ${run.frames} frames)`,
  );

  // 4 — a link nobody should be able to race
  await open('#d=2026-08-14&g=not-a-run');
  const junk = await page.evaluate(() => window.__vaults().hasGhost);
  check(junk === false, 'a corrupt link is refused rather than half-raced');
  check(
    (await page.locator('#v-ghost-time').textContent()).includes('not a run'),
    'and the page says so',
  );

  await open(link);
  await page.evaluate(() => window.__begin());
  await page.waitForTimeout(400);
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
  console.log('  the ghost races');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
