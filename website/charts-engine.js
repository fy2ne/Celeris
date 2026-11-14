window.CelerisCharts = (function () {
  const NS = "http://www.w3.org/2000/svg";
  const FG = "var(--color-fg-default)";
  const MUTED = "var(--color-fg-muted)";
  const SUBTLE = "var(--color-fg-subtle)";
  const GRID = "var(--color-border-default)";
  const AXIS = "var(--color-border-strong)";

  function el(tag, attrs = {}, text = null) {
    const node = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v !== undefined && v !== null) node.setAttribute(k, String(v));
    }
    if (text !== null) node.textContent = text;
    return node;
  }

  function makeTooltip(svg, width) {
    const g = el("g", { style: "pointer-events:none;display:none;" });
    const bg = el("rect", {
      rx: 6,
      ry: 6,
      height: 28,
      fill: "var(--color-surface-elevated)",
      stroke: "var(--color-border-strong)",
      "stroke-width": 1,
    });
    const dot = el("circle", { r: 3.5, cy: -14 });
    const valTxt = el("text", {
      y: -10,
      fill: FG,
      "font-size": 11,
      "font-weight": 600,
      "font-family": "var(--font-mono)",
    });
    const lblTxt = el("text", {
      y: -10,
      fill: MUTED,
      "font-size": 10,
      "font-family": "var(--font-sans)",
      "text-anchor": "end",
    });
    g.append(bg, dot, valTxt, lblTxt);
    svg.appendChild(g);

    return {
      show(x, y, valText, label, color) {
        const boxW = Math.max(146, valText.length * 7.2 + label.length * 6.0 + 36);
        const cx = Math.max(boxW / 2 + 8, Math.min(width - boxW / 2 - 8, x));
        const cy = Math.max(36, y - 8);
        g.setAttribute("transform", `translate(${cx.toFixed(1)},${cy.toFixed(1)})`);
        bg.setAttribute("x", (-boxW / 2).toFixed(1));
        bg.setAttribute("y", "-28");
        bg.setAttribute("width", boxW.toFixed(1));
        dot.setAttribute("cx", (-boxW / 2 + 12).toFixed(1));
        dot.setAttribute("fill", color);
        valTxt.setAttribute("x", (-boxW / 2 + 21).toFixed(1));
        valTxt.textContent = valText;
        lblTxt.setAttribute("x", (boxW / 2 - 10).toFixed(1));
        lblTxt.textContent = label;
        g.style.display = "block";
      },
      hide() {
        g.style.display = "none";
      },
    };
  }

  function renderGroupedBar(container, cfg) {
    const w = 860, h = 330, padL = 78, padR = 24, padT = 26, padB = 46;
    const pw = w - padL - padR, ph = h - padT - padB;
    const svg = el("svg", { viewBox: `0 0 ${w} ${h}`, class: "benchmark-svg" });

    const allVals = cfg.series.flatMap((s) => s.data);
    const maxVal = cfg.logScale ? Math.log10(Math.max(...allVals, 10)) : Math.max(...allVals, 1);
    const minLog = 0.7;

    const yPos = (v) => {
      if (cfg.logScale) {
        const lv = Math.log10(Math.max(v, 5));
        const t = (lv - minLog) / (Math.max(maxVal, 4.8) - minLog);
        return padT + ph * (1 - t);
      }
      return padT + ph * (1 - v / (maxVal * 1.14));
    };

    const ticks = cfg.logScale ? [10, 100, 1000, 10000, 50000] : cfg.ticks;
    ticks.forEach((t) => {
      const yp = yPos(t);
      svg.appendChild(el("line", {
        x1: padL, y1: yp, x2: w - padR, y2: yp,
        stroke: GRID, "stroke-width": 1, "stroke-dasharray": "3 3",
      }));
      const txt = cfg.logScale ? (t < 1000 ? `${t}` : `${t / 1000}k`) : `${t}${cfg.unit || ""}`;
      svg.appendChild(el("text", {
        x: padL - 10, y: yp + 4, "text-anchor": "end", fill: SUBTLE,
        "font-size": 11, "font-family": "var(--font-mono)",
      }, txt));
    });
    svg.appendChild(el("line", { x1: padL, y1: padT + ph, x2: w - padR, y2: padT + ph, stroke: AXIS, "stroke-width": 1 }));

    const gw = pw / cfg.x.length;
    const bw = Math.min(42, (gw * 0.66) / cfg.series.length);
    const gap = 8;

    const bars = [];
    cfg.x.forEach((xLbl, gi) => {
      const cx = padL + gw * (gi + 0.5);
      svg.appendChild(el("text", {
        x: cx, y: h - 14, "text-anchor": "middle", fill: MUTED,
        "font-size": 12, "font-weight": 500, "font-family": "var(--font-sans)",
      }, xLbl));

      const totalW = cfg.series.length * bw + (cfg.series.length - 1) * gap;
      const sx = cx - totalW / 2;

      cfg.series.forEach((s, si) => {
        const val = s.data[gi];
        const bx = sx + si * (bw + gap);
        const by = yPos(val);
        const bh = Math.max(padT + ph - by, 2);
        const rect = el("rect", {
          x: bx, y: by, width: bw, height: bh, rx: 3,
          fill: s.color, opacity: 0.9, class: "chart-bar",
        });
        const lbl = el("text", {
          x: bx + bw / 2, y: by - 7, "text-anchor": "middle", fill: FG,
          "font-size": 10.5, "font-family": "var(--font-mono)",
        }, cfg.formatVal(val));
        bars.push({ rect, bx, by, val, s, xLbl });
        svg.append(rect, lbl);
      });
    });

    const tip = makeTooltip(svg, w);
    bars.forEach(({ rect, bx, by, val, s, xLbl }) => {
      rect.addEventListener("mouseenter", () => {
        rect.setAttribute("opacity", "1");
        tip.show(bx + bw / 2, by, cfg.formatVal(val) + (cfg.unit || ""), `${s.name} / ${xLbl}`, s.color);
      });
      rect.addEventListener("mouseleave", () => {
        rect.setAttribute("opacity", "0.9");
        tip.hide();
      });
    });

    container.innerHTML = "";
    container.appendChild(svg);
  }

  function renderLollipopOrDumbbell(container, cfg) {
    const w = 860, h = 250, padL = 142, padR = 40, padT = 20, padB = 38;
    const pw = w - padL - padR, ph = h - padT - padB;
    const svg = el("svg", { viewBox: `0 0 ${w} ${h}`, class: "benchmark-svg" });

    const allVals = cfg.series.flatMap((s) => s.data);
    const maxVal = Math.max(...allVals, 1) * 1.1;
    const xPos = (v) => padL + (v / maxVal) * pw;

    for (let i = 0; i <= 4; i++) {
      const val = (maxVal / 4) * i;
      const xp = xPos(val);
      svg.appendChild(el("line", {
        x1: xp, y1: padT, x2: xp, y2: padT + ph,
        stroke: GRID, "stroke-width": 1, "stroke-dasharray": "3 3",
      }));
      svg.appendChild(el("text", {
        x: xp, y: h - 12, "text-anchor": "middle", fill: SUBTLE,
        "font-size": 11, "font-family": "var(--font-mono)",
      }, `${cfg.formatVal(val)}${cfg.unit || ""}`));
    }

    const rowH = ph / cfg.x.length;
    const dots = [];
    cfg.x.forEach((lbl, idx) => {
      const cy = padT + rowH * (idx + 0.5);
      svg.appendChild(el("text", {
        x: padL - 14, y: cy + 4, "text-anchor": "end", fill: MUTED,
        "font-size": 12, "font-weight": 500, "font-family": "var(--font-sans)",
      }, lbl));

      const xs = cfg.series.map((s) => xPos(s.data[idx]));
      const minX = Math.min(...xs), maxX = Math.max(...xs);
      svg.appendChild(el("line", {
        x1: cfg.series.length > 1 ? minX : padL,
        y1: cy, x2: maxX, y2: cy,
        stroke: AXIS, "stroke-width": 2, "stroke-linecap": "round",
      }));

      cfg.series.forEach((s) => {
        const val = s.data[idx];
        const cx = xPos(val);
        const c = el("circle", {
          cx, cy, r: 6, fill: s.color, stroke: "var(--color-surface-default)", "stroke-width": 2, class: "chart-dot",
        });
        dots.push({ c, cx, cy, val, s, lbl });
        svg.appendChild(c);
      });
    });

    const tip = makeTooltip(svg, w);
    dots.forEach(({ c, cx, cy, val, s, lbl }) => {
      c.addEventListener("mouseenter", () => {
        c.setAttribute("r", "8");
        tip.show(cx, cy, cfg.formatVal(val) + (cfg.unit || ""), `${s.name} / ${lbl}`, s.color);
      });
      c.addEventListener("mouseleave", () => {
        c.setAttribute("r", "6");
        tip.hide();
      });
    });

    container.innerHTML = "";
    container.appendChild(svg);
  }

  function renderLineChart(container, cfg) {
    const w = 860, h = 320, padL = 66, padR = 24, padT = 24, padB = 42;
    const pw = w - padL - padR, ph = h - padT - padB;
    const svg = el("svg", { viewBox: `0 0 ${w} ${h}`, class: "benchmark-svg" });

    const maxX = Math.max(...cfg.xVals, 1);
    const maxY = cfg.maxY;
    const xPos = (x) => padL + (x / maxX) * pw;
    const yPos = (y) => padT + ph * (1 - Math.min(y, maxY) / maxY);

    const defs = el("defs");
    cfg.series.forEach((s, idx) => {
      const grad = el("linearGradient", { id: `cg-${idx}`, x1: "0", y1: "0", x2: "0", y2: "1" });
      grad.appendChild(el("stop", { offset: "0%", "stop-color": s.color, "stop-opacity": "0.18" }));
      grad.appendChild(el("stop", { offset: "100%", "stop-color": s.color, "stop-opacity": "0" }));
      defs.appendChild(grad);
    });
    svg.appendChild(defs);

    cfg.ticks.forEach((t) => {
      const yp = yPos(t);
      svg.appendChild(el("line", {
        x1: padL, y1: yp, x2: w - padR, y2: yp,
        stroke: GRID, "stroke-width": 1, "stroke-dasharray": "3 3",
      }));
      svg.appendChild(el("text", {
        x: padL - 10, y: yp + 4, "text-anchor": "end", fill: SUBTLE,
        "font-size": 11, "font-family": "var(--font-mono)",
      }, `${t}${cfg.unit || ""}`));
    });
    svg.appendChild(el("line", { x1: padL, y1: padT + ph, x2: w - padR, y2: padT + ph, stroke: AXIS, "stroke-width": 1 }));

    [0, 0.25, 0.5, 0.75, 1].forEach((f) => {
      const xv = Math.round(maxX * f);
      svg.appendChild(el("text", {
        x: xPos(xv), y: h - 12, "text-anchor": "middle", fill: SUBTLE,
        "font-size": 11, "font-family": "var(--font-mono)",
      }, `${xv}${cfg.xUnit || ""}`));
    });

    const crosshair = el("line", {
      x1: padL, y1: padT, x2: padL, y2: padT + ph,
      stroke: AXIS, "stroke-width": 1, "stroke-dasharray": "2 2", style: "display:none;pointer-events:none;",
    });
    svg.appendChild(crosshair);

    const dots = [];
    cfg.series.forEach((s, idx) => {
      const pts = s.points.filter((p) => p.y !== null);
      if (!pts.length) return;
      const lineD = pts.map((p, i) => `${i === 0 ? "M" : "L"}${xPos(p.x).toFixed(1)},${yPos(p.y).toFixed(1)}`).join(" ");
      const areaD = `${lineD} L${xPos(pts[pts.length - 1].x).toFixed(1)},${(padT + ph).toFixed(1)} L${xPos(pts[0].x).toFixed(1)},${(padT + ph).toFixed(1)} Z`;
      svg.appendChild(el("path", { d: areaD, fill: `url(#cg-${idx})` }));
      svg.appendChild(el("path", { d: lineD, fill: "none", stroke: s.color, "stroke-width": 2, "stroke-linejoin": "round" }));

      pts.forEach((p) => {
        const cx = xPos(p.x), cy = yPos(p.y);
        const hit = el("circle", { cx, cy, r: 4.5, fill: s.color, opacity: 0.01, class: "chart-dot" });
        dots.push({ hit, cx, cy, p, s });
        svg.appendChild(hit);
      });
    });

    const tip = makeTooltip(svg, w);
    dots.forEach(({ hit, cx, cy, p, s }) => {
      hit.addEventListener("mouseenter", () => {
        hit.setAttribute("opacity", "1");
        crosshair.setAttribute("x1", cx);
        crosshair.setAttribute("x2", cx);
        crosshair.style.display = "block";
        tip.show(cx, cy, `${p.y}${cfg.unit || ""}`, `${s.name} @ ${p.x}${cfg.xUnit || ""}`, s.color);
      });
      hit.addEventListener("mouseleave", () => {
        hit.setAttribute("opacity", "0.01");
        crosshair.style.display = "none";
        tip.hide();
      });
    });

    container.innerHTML = "";
    container.appendChild(svg);
  }

  return { renderGroupedBar, renderLollipopOrDumbbell, renderLineChart };
})();
