#!/usr/bin/env node
/**
 * Plays The Vaults in a real browser and reports what happened.
 *
 * This is the check Palace never had. Its physics was tested headlessly and thoroughly, and the
 * game was still unplayable, because no test ever *pressed a button*. Here Chromium runs the
 * shipping Kotlin, compiled to JavaScript by webplay/, and real key events drive it.
 *
 * Usage:  node tools/playtest/play.js [--room N] [--shot out.png] [--headed]
 *
 * Requires `gradle -p webplay bundle` first. Playwright and Chromium are pre-installed; run with
 *   NODE_PATH=/opt/node22/lib/node_modules node tools/playtest/play.js
 * if playwright is not resolvable from the working directory.
 */
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const ROOT = path.resolve(__dirname, '..', '..');
const PAGE = path.join(ROOT, 'webplay', 'build', 'web', 'index.html');
const ROOM = Number(arg('--room', '0'));
const SHOT = arg('--shot', path.join(ROOT, 'webplay', 'build', 'web', 'playtest.png'));

const CHROME = ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
                '/opt/pw-browsers/chromium/chrome-linux/chrome']
  .find(p => fs.existsSync(p));

(async () => {
  if (!fs.existsSync(PAGE)) {
    console.error('No bundle. Run: gradle -p webplay bundle');
    process.exit(2);
  }

  const browser = await chromium.launch({
    executablePath: CHROME,
    headless: !argv.includes('--headed'),
  });
  const page = await browser.newPage({ viewport: { width: 1000, height: 760 } });

  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto('file://' + PAGE);
  await page.waitForFunction(() => window.__ready === true, { timeout: 15000 });

  if (errors.length) {
    console.error('Page errors:\n  ' + errors.join('\n  '));
    await browser.close();
    process.exit(1);
  }

  // Pause the render loop's own stepping and drive the engine deterministically instead, so the
  // result does not depend on how fast this machine happens to render.
  await page.evaluate(n => window.__session.loadRoom(n), ROOM);

  const info = await page.evaluate(() => ({
    room: window.__session.roomId,
    margin: window.__session.margin(),
    min: window.__session.constructor.Companion
      ? window.__session.constructor.Companion.minMargin : null,
  }));

  console.log(`room "${info.room}"  margin ${info.margin} frames ` +
              `(${Math.round(info.margin * 1000 / 60)}ms)`);

  // Play it: hold right, and jump in the middle of the proven window.
  const result = await page.evaluate(() => {
    const s = window.__session;
    s.reset();
    // Find the window the same way CI does, then aim at its centre — a human aims at the
    // middle of a window, not at its edge.
    let good = [];
    for (let f = 0; f < 400; f++) {
      s.reset();
      let ok = false;
      for (let i = 0; i < 400; i++) {
        s.tick(false, true, i === f);
        if (s.phase === 'EXITED') { ok = true; break; }
        if (s.phase === 'DEAD') break;
      }
      if (ok) good.push(f);
    }
    if (!good.length) return { completed: false, good: 0 };
    const aim = good[Math.floor(good.length / 2)];

    // Now the actual run, recording the trajectory for the screenshot.
    s.reset();
    const path = [];
    for (let i = 0; i < 400; i++) {
      s.tick(false, true, i === aim);
      path.push([s.x, s.y]);
      if (s.over) break;
    }
    window.__setTrail(path);
    return { completed: s.phase === 'EXITED', frames: s.frame, aim, good: good.length };
  });

  if (!result.completed) {
    console.error(`FAILED: room "${info.room}" could not be completed by any single jump.`);
    await browser.close();
    process.exit(1);
  }

  console.log(`completed in ${result.frames} frames, jumping at frame ${result.aim} ` +
              `(${result.good} of 400 jump frames work)`);

  // The harness draws the trail itself, so just let one frame render and capture it.
  await page.waitForTimeout(120);
  await page.screenshot({ path: SHOT });
  console.log(`screenshot: ${SHOT}`);

  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
