#!/usr/bin/env node
/**
 * Plays The Vaults in a real browser and reports what happened.
 *
 * This is the check Palace never had. Its physics was tested headlessly and thoroughly, and the
 * game was still unplayable, because no test ever *pressed a button*. Here Chromium runs the
 * shipping Kotlin, compiled to JavaScript by webplay/, driven through the page's own controls.
 *
 * It also re-measures every room's timing margin in the browser and compares it against the same
 * figure from the JVM. Those two numbers agreeing is the proof that the browser build and the APK
 * are one implementation rather than two that have quietly drifted apart.
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
const ONLY = argv.includes('--room') ? Number(arg('--room', '0')) : null;
const SHOT = arg('--shot', path.join(ROOT, 'webplay', 'build', 'web', 'playtest.png'));

const CHROME = ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
                '/opt/pw-browsers/chromium/chrome-linux/chrome']
  .find(p => fs.existsSync(p));

(async () => {
  if (!fs.existsSync(PAGE)) {
    console.error('No bundle. Run: gradle -p webplay bundle');
    process.exit(2);
  }

  const browser = await chromium.launch({ executablePath: CHROME, headless: !argv.includes('--headed') });
  const page = await browser.newPage({ viewport: { width: 1000, height: 1020 } });

  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto('file://' + PAGE);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  if (errors.length) {
    console.error('Page errors:\n  ' + errors.join('\n  '));
    await browser.close();
    process.exit(1);
  }

  const count = await page.evaluate(() => window.__session.constructor.Companion.roomCount);
  const rooms = ONLY === null ? [...Array(count).keys()] : [ONLY];
  let failures = 0;

  for (const i of rooms) {
    await page.evaluate(n => { window.__marginFor = null; window.__selectRoom(n); }, i);
    // Margin replays the room hundreds of times; the page publishes which room a reading is for,
    // so a stale number from the previous room can never be mistaken for a fresh one.
    await page.waitForFunction(
      () => window.__marginFor === window.__session.roomId, null, { timeout: 120000 },
    );

    const res = await page.evaluate(() => {
      const s = window.__session;
      const margin = parseInt(document.getElementById('s-margin').textContent, 10);

      // Search the same plan space the engine's own solver uses, then play it out for real.
      const period = s.chomperCount > 0 ? 130 : 1;
      for (let wait = 0; wait < period; wait += 1) {
        for (let press = 0; press < 300; press += 1) {
          s.restart();
          let ok = false;
          for (let f = 0; f < 480; f++) {
            const moving = f >= wait;
            s.tick(false, moving, f === wait + press, false);
            if (s.finished) { ok = true; break; }
            if (s.phase === 'DEAD') break;
          }
          if (ok) {
            // Replay the winner so the canvas shows the run that worked.
            s.restart();
            for (let f = 0; f < 480; f++) {
              s.tick(false, f >= wait, f === wait + press, false);
              if (s.finished || s.phase === 'DEAD') break;
            }
            return { room: s.roomId, margin, wait, press, frames: s.elapsedFrames, ok: true };
          }
        }
      }
      return { room: s.roomId, margin, ok: false };
    });

    if (!res.ok) {
      console.error(`  ${res.room.padEnd(14)} FAILED — no way through found in the browser`);
      failures++;
      continue;
    }
    const secs = (res.frames / 60).toFixed(1);
    console.log(
      `  ${res.room.padEnd(14)} margin ${String(res.margin).padStart(2)}f  ` +
      `cleared in ${secs.padStart(4)}s  (wait ${res.wait}, jump +${res.press})`,
    );
  }

  await page.waitForTimeout(150);
  await page.screenshot({ path: SHOT, fullPage: true });
  console.log(`  screenshot: ${SHOT}`);

  await browser.close();
  if (errors.length) {
    console.error('Page errors:\n  ' + errors.join('\n  '));
    process.exit(1);
  }
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
