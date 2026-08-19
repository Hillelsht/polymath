/*
 * Drawing a room. One implementation, used by the tuning harness and by the daily.
 *
 * Extracted rather than copied, for the reason this whole project keeps relearning: two drawings
 * of the same thing drift, and the drift is invisible until someone is racing a ghost that appears
 * to stand on a ledge their own runner falls through.
 *
 * It reads a Session and paints; it holds no game state of its own beyond the trail, which is a
 * drawing concern. Colours are the stone palette the game shipped with — this is the game's own
 * world, not the portal's shell.
 */
const VaultView = (canvas, options = {}) => {
  const ctx = canvas.getContext('2d');
  const W = canvas.width, H = canvas.height;
  // The authored rooms run to ~720 units wide and 64 tall, and these are the numbers they were
  // drawn at. Generated rooms are not that shape — three beats and two drops reach 1,200 wide and
  // 192 deep — so the scale is a ceiling now rather than a constant.
  const SCALE = options.scale ?? 1.2, OX = options.ox ?? 22, OY = options.oy ?? 112;

  /** How far a jump rises, which is the headroom a room needs above its highest floor. */
  const RISE = 48, EDGE = 30;

  let scale = SCALE, oy = OY, deepest = 0, fittedTo = null;
  const sx = x => OX + x * scale, sy = y => oy + y * scale;

  /**
   * Sizes the drawing to the room, once per room rather than once per frame.
   *
   * Never magnifies: a room that already fits is drawn at the size the game was tuned at, so every
   * authored room looks exactly as it did before generated ones existed. Only a room too big for
   * the canvas is stepped down, and then far enough to keep the jump arc on screen as well —
   * clipping the top of a jump would be a worse bug than clipping the far wall, because you would
   * lose sight of the runner rather than of the scenery.
   */
  function fit(session) {
    if (fittedTo === session.roomId) return;
    fittedTo = session.roomId;
    let right = session.exitX;
    deepest = 0;
    for (let i = 0; i < session.ledgeCount; i++) {
      right = Math.max(right, session.ledgeX1(i));
      deepest = Math.max(deepest, session.ledgeY(i));
    }
    scale = Math.min(SCALE, (W - OX * 2) / Math.max(right, 1), (H - EDGE * 2) / (RISE + deepest));
    oy = Math.min(OY, H - EDGE - deepest * scale);
  }

  let trail = [];

  function mix(a, b, t) {
    const p = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
    const [r1, g1, b1] = p(a), [r2, g2, b2] = p(b);
    return `rgb(${r1 + (r2 - r1) * t | 0},${g1 + (g2 - g1) * t | 0},${b1 + (b2 - b1) * t | 0})`;
  }

  /** A runner, live or ghostly. Amber on the ground, cold blue in the air, green while hanging. */
  function figure(x, y, { hanging, grounded, dead, ghost }) {
    const px = sx(x), py = sy(y);
    ctx.save();
    if (ghost) ctx.globalAlpha = dead ? 0.18 : 0.42;
    ctx.fillStyle = ghost ? '#8FA6D8'
      : dead ? '#5A3038' : hanging ? '#5FB89A' : grounded ? '#E8A33D' : '#6E93E0';
    if (hanging) {
      ctx.fillRect(px - 6, py, 12, 26);           // below the lip, holding on
      ctx.fillRect(px - 9, py - 3, 18, 3);
    } else if (dead) {
      ctx.fillRect(px - 13, py - 7, 26, 7);       // face down
    } else {
      ctx.fillRect(px - 6, py - 28, 12, 28);
    }
    ctx.restore();
  }

  return {
    reset() { trail = []; fittedTo = null; },

    /** Call once per simulated frame, so the arc is drawn at the engine's rate not the screen's. */
    record(session) {
      trail.push([session.x, session.y]);
      if (trail.length > 900) trail.shift();
    },

    draw(session, { showTrail = true, showGhost = true } = {}) {
      fit(session);
      ctx.fillStyle = '#0F1014'; ctx.fillRect(0, 0, W, H);
      // The void under the world. Kept below the lowest floor as well as at its authored depth,
      // so a room that drops further than any hand-built one does not have it painted over its
      // own stone.
      const floor = Math.max(sy(104), sy(deepest) + 26);
      ctx.fillStyle = '#0A0B0E'; ctx.fillRect(0, floor, W, H - floor);

      // ledges — torchlight from above, so the top edge is lit and the body is not
      for (let i = 0; i < session.ledgeCount; i++) {
        if (session.ledgeBroken(i)) continue;
        const x0 = sx(session.ledgeX0(i)), x1 = sx(session.ledgeX1(i)), y = sy(session.ledgeY(i));
        const loose = session.ledgeCollapsing(i), strain = session.ledgeStrain(i);
        ctx.fillStyle = loose ? '#2A2530' : '#22242E';
        ctx.fillRect(x0, y, x1 - x0, 20);
        // A tile running out of patience warms toward the torch colour, then to rust.
        ctx.fillStyle = strain > 0 ? mix('#4A4050', '#C4563F', strain) : (loose ? '#4A4050' : '#3A3E4E');
        ctx.fillRect(x0, y, x1 - x0, strain > 0 ? 2.5 + 2 * strain : 2.5);
        if (loose) {
          ctx.strokeStyle = '#15161B'; ctx.lineWidth = 1;
          for (let g = x0 + 18; g < x1 - 4; g += 22) {
            ctx.beginPath(); ctx.moveTo(g, y); ctx.lineTo(g, y + 20); ctx.stroke();
          }
        }
      }

      // blades — shut is a solid bar, open is a stub, and the cycle shows as a filling sliver
      for (let i = 0; i < session.chomperCount; i++) {
        const bx = sx(session.chomperX(i)), by = sy(session.chomperY(i));
        const reach = session.chomperReach(i) * SCALE, hw = session.chomperHalfWidth(i) * SCALE;
        const open = session.chomperOpen(i), ph = session.chomperPhase(i);
        ctx.fillStyle = open ? '#2E3140' : '#C4563F';
        const h = open ? reach * 0.16 : reach;
        ctx.fillRect(bx - hw, by - h, hw * 2, h);
        // housing, so an open blade still reads as a threat rather than as nothing
        ctx.strokeStyle = '#3A3E4E'; ctx.lineWidth = 1;
        ctx.strokeRect(bx - hw - 1.5, by - reach, hw * 2 + 3, reach);
        ctx.fillStyle = open ? '#5FB89A' : '#C4563F';
        ctx.fillRect(bx - hw - 1.5, by - reach - 4, (hw * 2 + 3) * ph, 2);
      }

      // the way out
      const ex = sx(session.exitX);
      ctx.strokeStyle = '#5FB89A'; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(ex, sy(0) - 54); ctx.lineTo(ex, sy(0) + 20); ctx.stroke();

      // the path just travelled — an arc is easier to judge as a shape than as a feeling
      if (showTrail && trail.length > 1) {
        ctx.strokeStyle = 'rgba(232,163,61,.34)'; ctx.lineWidth = 1.5; ctx.setLineDash([4, 4]);
        ctx.beginPath();
        trail.forEach(([x, y], i) => (i ? ctx.lineTo(sx(x), sy(y)) : ctx.moveTo(sx(x), sy(y))));
        ctx.stroke(); ctx.setLineDash([]);
      }

      // Drawn under the live runner, so the person playing is never hidden by the person they are
      // chasing — the ghost is information, not an obstacle.
      if (showGhost && session.hasGhost && !session.ghostSpent) {
        figure(session.ghostX, session.ghostY, {
          hanging: session.ghostHanging, dead: session.ghostDead, ghost: true,
        });
      }

      const dead = session.phase === 'DEAD';
      figure(session.x, session.y, {
        hanging: session.hanging, grounded: session.grounded, dead,
      });

      // an unspent press, waiting for a frame it can be used on — the whole fix, made visible
      if (session.jumpBufferedAgo >= 0 && !dead) {
        ctx.fillStyle = '#5FB89A';
        ctx.beginPath();
        ctx.arc(sx(session.x), sy(session.y) - (session.hanging ? -34 : 36), 3.5, 0, 7);
        ctx.fill();
      }
    },

    /** A full-width curtain with a headline and a line under it, for the end of a run. */
    banner(title, note) {
      ctx.fillStyle = 'rgba(15,16,20,.82)'; ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = '#E6E1D6'; ctx.textAlign = 'center';
      ctx.font = '600 30px ui-serif, Georgia, serif';
      ctx.fillText(title, W / 2, H / 2 - 6);
      if (note) {
        ctx.font = '14px ui-monospace, Menlo, monospace';
        ctx.fillStyle = '#8A8778';
        ctx.fillText(note, W / 2, H / 2 + 22);
      }
      ctx.textAlign = 'left';
    },
  };
};

globalThis.VaultView = VaultView;
