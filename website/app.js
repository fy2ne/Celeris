(async function () {
  const res = await fetch("./data/benchmarks.json");
  const data = await res.json();

  const tabBtns = document.querySelectorAll(".ds-tab");
  const stageEl = document.getElementById("bench-stage");
  const tableEl = document.getElementById("bench-table");

  const COLORS = {
    fifo: "#f07a6a",
    java: "#38bdf8",
    rust: "#a98fff",
    amber: "#f2c46b",
  };

  let activeTab = "burst";
  let burstMetric = "p99";
  let burstChartType = "grouped-bar";
  let tcpChartType = "grouped-bar";
  let compLevel = 6;

  function cardShell({ title, subtitle, direction, controlsHtml, legend, source }) {
    stageEl.innerHTML = `
      <figure class="benchmark-minimal-card">
        <figcaption class="benchmark-minimal-head">
          <div class="benchmark-minimal-title-row">
            <div class="benchmark-minimal-title">${title}</div>
            <div class="benchmark-minimal-controls">
              ${controlsHtml || ""}
              <span class="benchmark-minimal-dir">${direction}</span>
            </div>
          </div>
          <p class="benchmark-minimal-sub">${subtitle}</p>
        </figcaption>
        <div class="benchmark-svg-wrap" id="chart-mount"></div>
        <div class="benchmark-minimal-foot">
          <div class="benchmark-minimal-legend">
            ${legend.map(([lbl, c]) => `
              <div class="benchmark-legend-pill">
                <span class="benchmark-legend-swatch" style="background:${c};"></span>
                <span>${lbl}</span>
              </div>
            `).join("")}
          </div>
          <div class="benchmark-minimal-source">${source}</div>
        </div>
      </figure>
    `;
    return document.getElementById("chart-mount");
  }

  function renderBurst() {
    const sizes = ["256", "512", "1024"];
    const mount = cardShell({
      title: `Express Queue Delay (${burstMetric.toUpperCase()}) Under Chunk Burst`,
      subtitle: `32 KB paced wire slices, n=60 per engine per burst size, ${data.burst.decode_errors} decode errors`,
      direction: "↓ lower is better",
      source: "Source: docs/benchmarks/data/bench_burst_latency.csv",
      legend: [
        ["Vanilla FIFO", COLORS.fifo],
        ["Celeris java-engine", COLORS.java],
        ["Celeris rust-native", COLORS.rust],
      ],
      controlsHtml: `
        <div class="pill-group">
          ${["p50", "p95", "p99", "max"].map((m) => `
            <button class="pill-btn ${burstMetric === m ? "active" : ""}" data-metric="${m}">${m.toUpperCase()}</button>
          `).join("")}
        </div>
        <div class="pill-group">
          <button class="pill-btn ${burstChartType === "grouped-bar" ? "active" : ""}" data-ctype="grouped-bar">Bar</button>
          <button class="pill-btn ${burstChartType === "dumbbell" ? "active" : ""}" data-ctype="dumbbell">Dumbbell</button>
        </div>
      `,
    });

    stageEl.querySelectorAll("[data-metric]").forEach((b) =>
      b.addEventListener("click", () => { burstMetric = b.dataset.metric; renderBurst(); })
    );
    stageEl.querySelectorAll("[data-ctype]").forEach((b) =>
      b.addEventListener("click", () => { burstChartType = b.dataset.ctype; renderBurst(); })
    );

    const cfg = {
      x: sizes.map((k) => `${k} KB Burst`),
      unit: " us",
      logScale: burstChartType === "grouped-bar",
      formatVal: (v) => Math.round(v).toLocaleString("en-US"),
      series: [
        { name: "Vanilla FIFO", color: COLORS.fifo, data: sizes.map((k) => data.burst.sizes[k].baseline_fifo[burstMetric]) },
        { name: "Celeris java-engine", color: COLORS.java, data: sizes.map((k) => data.burst.sizes[k].celeris_java[burstMetric]) },
        { name: "Celeris rust-native", color: COLORS.rust, data: sizes.map((k) => data.burst.sizes[k].celeris_rust[burstMetric]) },
      ],
    };

    if (burstChartType === "dumbbell") CelerisCharts.renderLollipopOrDumbbell(mount, cfg);
    else CelerisCharts.renderGroupedBar(mount, cfg);

    tableEl.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Workload</th>
            <th class="num">Vanilla FIFO (${burstMetric.toUpperCase()})</th>
            <th class="num">Celeris java-engine</th>
            <th class="num">Celeris rust-native</th>
          </tr>
        </thead>
        <tbody>
          ${sizes.map((kb) => {
            const s = data.burst.sizes[kb];
            return `
              <tr>
                <td>${kb} KB Burst</td>
                <td class="num">${s.baseline_fifo[burstMetric].toLocaleString("en-US")} us</td>
                <td class="num">${s.celeris_java[burstMetric].toLocaleString("en-US")} us</td>
                <td class="num" style="color:var(--color-brand-default);font-weight:600;">${s.celeris_rust[burstMetric].toLocaleString("en-US")} us</td>
              </tr>
            `;
          }).join("")}
        </tbody>
      </table>
    `;
  }

  function renderTcp() {
    const phases = [
      ["idle", "Idle (No Burst)"],
      ["burst_512kb", "512 KB Burst"],
      ["burst_1024kb", "1024 KB Burst"],
    ];
    const mount = cardShell({
      title: "Real TCP Socket KeepAlive P95 RTT",
      subtitle: `Loopback socket with SO_RCVBUF=8 KB (${data.tcp_loopback.total_rows} samples). Idle parity holds; 1 MB burst spikes FIFO to 15.25 ms`,
      direction: "↓ lower is better",
      source: "Source: docs/benchmarks/data/bench_tcp_*.csv",
      legend: [
        ["Vanilla FIFO", COLORS.fifo],
        ["Celeris java-engine", COLORS.java],
        ["Celeris rust-native", COLORS.rust],
      ],
      controlsHtml: `
        <div class="pill-group">
          <button class="pill-btn ${tcpChartType === "grouped-bar" ? "active" : ""}" data-tcptype="grouped-bar">Bar</button>
          <button class="pill-btn ${tcpChartType === "dumbbell" ? "active" : ""}" data-tcptype="dumbbell">Dumbbell</button>
        </div>
      `,
    });

    stageEl.querySelectorAll("[data-tcptype]").forEach((b) =>
      b.addEventListener("click", () => { tcpChartType = b.dataset.tcptype; renderTcp(); })
    );

    const cfg = {
      x: phases.map(([, l]) => l),
      unit: " ms",
      ticks: [0, 4, 8, 12, 16],
      formatVal: (v) => v.toFixed(2),
      series: [
        { name: "Vanilla FIFO", color: COLORS.fifo, data: phases.map(([k]) => data.tcp_loopback.phases[k].baseline_fifo.p95) },
        { name: "Celeris java-engine", color: COLORS.java, data: phases.map(([k]) => data.tcp_loopback.phases[k].celeris_java.p95) },
        { name: "Celeris rust-native", color: COLORS.rust, data: phases.map(([k]) => data.tcp_loopback.phases[k].celeris_rust.p95) },
      ],
    };

    if (tcpChartType === "dumbbell") CelerisCharts.renderLollipopOrDumbbell(mount, cfg);
    else CelerisCharts.renderGroupedBar(mount, cfg);

    tableEl.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Phase</th>
            <th class="num">Vanilla FIFO (P50 / P95)</th>
            <th class="num">Celeris java-engine (P50 / P95)</th>
            <th class="num">Celeris rust-native (P50 / P95)</th>
          </tr>
        </thead>
        <tbody>
          ${phases.map(([k, lbl]) => {
            const p = data.tcp_loopback.phases[k];
            return `
              <tr>
                <td>${lbl}</td>
                <td class="num">${p.baseline_fifo.p50.toFixed(2)} ms / ${p.baseline_fifo.p95.toFixed(2)} ms</td>
                <td class="num">${p.celeris_java.p50.toFixed(2)} ms / ${p.celeris_java.p95.toFixed(2)} ms</td>
                <td class="num" style="color:var(--color-brand-default);font-weight:600;">${p.celeris_rust.p50.toFixed(2)} ms / ${p.celeris_rust.p95.toFixed(2)} ms</td>
              </tr>
            `;
          }).join("")}
        </tbody>
      </table>
    `;
  }

  function renderCompression() {
    const levels = [...new Set(data.compression_sweep.map((r) => r.zlib_level))].sort((a, b) => a - b);
    const thresholds = [-1, 64, 128, 256, 512];
    const filtered = data.compression_sweep.filter((r) => r.zlib_level === compLevel);

    const mount = cardShell({
      title: `Wire Savings vs. Compression Threshold (Zlib Level ${compLevel})`,
      subtitle: "424 mixed Minecraft packets (630,048 B raw), AES-128-CFB8 parity verified across every threshold",
      direction: "↑ higher is better",
      source: "Source: docs/benchmarks/data/bench_compression_sweep.csv",
      legend: [
        ["Celeris java-engine", COLORS.java],
        ["Celeris rust-native", COLORS.rust],
      ],
      controlsHtml: `
        <div class="pill-group">
          ${levels.map((l) => `
            <button class="pill-btn ${compLevel === l ? "active" : ""}" data-lvl="${l}">L${l}</button>
          `).join("")}
        </div>
      `,
    });

    stageEl.querySelectorAll("[data-lvl]").forEach((b) =>
      b.addEventListener("click", () => { compLevel = Number(b.dataset.lvl); renderCompression(); })
    );

    const getVal = (eng, th) => {
      const row = filtered.find((r) => r.engine === eng && r.threshold === th);
      return row ? Math.max(0, row.saved_pct) : 0;
    };

    CelerisCharts.renderGroupedBar(mount, {
      x: thresholds.map((t) => (t === -1 ? "Off (-1)" : `${t} B`)),
      unit: "%",
      ticks: [0, 25, 50, 75, 100],
      formatVal: (v) => v.toFixed(1),
      series: [
        { name: "Celeris java-engine", color: COLORS.java, data: thresholds.map((t) => getVal("celeris_java", t)) },
        { name: "Celeris rust-native", color: COLORS.rust, data: thresholds.map((t) => getVal("celeris_rust", t)) },
      ],
    });

    tableEl.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Engine</th>
            <th class="num">Threshold</th>
            <th class="num">Wire Bytes</th>
            <th class="num">Saved</th>
            <th class="num">Throughput</th>
            <th class="num">CFB8 Parity</th>
          </tr>
        </thead>
        <tbody>
          ${filtered.map((r) => `
            <tr>
              <td>${r.engine}</td>
              <td class="num">${r.threshold === -1 ? "Off (-1)" : r.threshold + " B"}</td>
              <td class="num">${r.wire_bytes.toLocaleString("en-US")} B</td>
              <td class="num" style="color:var(--color-brand-default);">${r.saved_pct.toFixed(2)}%</td>
              <td class="num">${r.throughput_mbps.toFixed(1)} Mbps</td>
              <td class="num">${r.cfb8_verified ? "OK" : "FAIL"}</td>
            </tr>
          `).join("")}
        </tbody>
      </table>
    `;
  }

  function renderLive() {
    const live = data.live_telemetry;
    const mount = cardShell({
      title: "Live Paper 26.3 (roxaura.mcsh.gg) — KeepAlive Interval vs. MC Ping",
      subtitle: `v0.1.0 java-engine · ${live.rows.toLocaleString("en-US")} telemetry rows · ${live.saved_pct.toFixed(1)}% wire saved · ${live.preempted_cnt.toLocaleString("en-US")} preemptions · 0 disconnects`,
      direction: "↓ lower is better",
      source: "Source: docs/benchmarks/data/telemetry_live.csv",
      legend: [
        ["KeepAlive Interval (ms)", COLORS.rust],
        ["MC Ping (ms)", COLORS.amber],
      ],
    });

    const kaPts = live.series.filter((s) => s.ka_ms !== null).map((s) => ({ x: s.t_sec, y: s.ka_ms }));
    const pingPts = live.series.filter((s) => s.mc_ping_ms !== null).map((s) => ({ x: s.t_sec, y: s.mc_ping_ms }));

    CelerisCharts.renderLineChart(mount, {
      xVals: live.series.map((s) => s.t_sec),
      xUnit: "s",
      maxY: 1250,
      ticks: [0, 300, 600, 900, 1200],
      unit: " ms",
      series: [
        { name: "KeepAlive", color: COLORS.rust, points: kaPts },
        { name: "MC Ping", color: COLORS.amber, points: pingPts },
      ],
    });

    tableEl.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Live Phase</th>
            <th class="num">KeepAlives</th>
            <th class="num">KA Interval P50</th>
            <th class="num">KA Interval P95</th>
            <th class="num">MC Ping P50</th>
          </tr>
        </thead>
        <tbody>
          ${Object.entries(live.phases).map(([phase, s]) => `
            <tr>
              <td><code class="mono">${phase}</code></td>
              <td class="num">${s.ka_n}</td>
              <td class="num">${s.ka_p50_ms.toFixed(1)} ms</td>
              <td class="num">${s.ka_p95_ms.toFixed(1)} ms</td>
              <td class="num">${s.mc_ping_p50_ms !== null ? s.mc_ping_p50_ms.toFixed(1) + " ms" : "—"}</td>
            </tr>
          `).join("")}
        </tbody>
      </table>
    `;
  }

  function render() {
    if (activeTab === "burst") renderBurst();
    else if (activeTab === "tcp") renderTcp();
    else if (activeTab === "compression") renderCompression();
    else renderLive();
  }

  tabBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      tabBtns.forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      activeTab = btn.dataset.tab;
      render();
    });
  });

  // CodeBlock copy buttons
  document.querySelectorAll("[data-copy]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const target = document.getElementById(btn.dataset.copy);
      if (!target) return;
      await navigator.clipboard.writeText(target.textContent);
      const prev = btn.textContent;
      btn.textContent = "Copied";
      setTimeout(() => { btn.textContent = prev; }, 1400);
    });
  });

  render();
})();
