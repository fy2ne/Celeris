(async function () {
  const res = await fetch("./data/benchmarks.json");
  const data = await res.json();

  const tabBtns = document.querySelectorAll(".tab-btn");
  const controlsEl = document.getElementById("bench-controls");
  const chartEl = document.getElementById("chart-stage");
  const tableEl = document.getElementById("bench-table");

  let activeTab = "burst";
  let burstMetric = "p99";
  let compLevel = 6;

  function renderBurst() {
    controlsEl.innerHTML = `
      <span style="color:var(--muted);font-size:13.5px;">Paced 32 KB wire slices, n=60 per engine, ${data.burst.decode_errors} decode errors</span>
      <div class="pill-group">
        ${["p50", "p95", "p99", "max"].map((m) => `
          <button class="pill-btn ${burstMetric === m ? "active" : ""}" data-metric="${m}">${m.toUpperCase()}</button>
        `).join("")}
      </div>
    `;
    controlsEl.querySelectorAll("[data-metric]").forEach((btn) => {
      btn.addEventListener("click", () => {
        burstMetric = btn.dataset.metric;
        renderBurst();
      });
    });

    chartEl.innerHTML = `<img src="./charts/chart_1_express_burst_latency.svg" alt="Express Burst Latency Chart">`;

    const rows = ["256", "512", "1024"].map((kb) => {
      const s = data.burst.sizes[kb];
      return `
        <tr>
          <td>${kb} KB Burst</td>
          <td class="num">${s.baseline_fifo[burstMetric].toLocaleString("en-US")} us</td>
          <td class="num">${s.celeris_java[burstMetric].toLocaleString("en-US")} us</td>
          <td class="num" style="color:var(--accent);font-weight:600;">${s.celeris_rust[burstMetric].toLocaleString("en-US")} us</td>
        </tr>
      `;
    }).join("");

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
        <tbody>${rows}</tbody>
      </table>
    `;
  }

  function renderTcp() {
    controlsEl.innerHTML = `
      <span style="color:var(--muted);font-size:13.5px;">Loopback socket with SO_RCVBUF=8 KB (${data.tcp_loopback.total_rows} KeepAlive samples)</span>
    `;
    chartEl.innerHTML = `<img src="./charts/chart_2_tcp_keepalive_rtt.svg" alt="TCP Loopback KeepAlive RTT Chart">`;

    const labels = [
      ["idle", "Idle (No Burst)"],
      ["burst_512kb", "512 KB Chunk Burst"],
      ["burst_1024kb", "1024 KB Chunk Burst"],
    ];
    const rows = labels.map(([k, lbl]) => {
      const p = data.tcp_loopback.phases[k];
      return `
        <tr>
          <td>${lbl}</td>
          <td class="num">${p.baseline_fifo.p50.toFixed(2)} ms / ${p.baseline_fifo.p95.toFixed(2)} ms</td>
          <td class="num">${p.celeris_java.p50.toFixed(2)} ms / ${p.celeris_java.p95.toFixed(2)} ms</td>
          <td class="num" style="color:var(--accent);font-weight:600;">${p.celeris_rust.p50.toFixed(2)} ms / ${p.celeris_rust.p95.toFixed(2)} ms</td>
        </tr>
      `;
    }).join("");

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
        <tbody>${rows}</tbody>
      </table>
    `;
  }

  function renderCompression() {
    const levels = [...new Set(data.compression_sweep.map((r) => r.zlib_level))].sort((a, b) => a - b);
    controlsEl.innerHTML = `
      <span style="color:var(--muted);font-size:13.5px;">424 mixed Minecraft packets (630,048 B raw), AES-128-CFB8 parity verified</span>
      <div class="pill-group">
        ${levels.map((lvl) => `
          <button class="pill-btn ${compLevel === lvl ? "active" : ""}" data-lvl="${lvl}">Zlib L${lvl}</button>
        `).join("")}
      </div>
    `;
    controlsEl.querySelectorAll("[data-lvl]").forEach((btn) => {
      btn.addEventListener("click", () => {
        compLevel = Number(btn.dataset.lvl);
        renderCompression();
      });
    });

    chartEl.innerHTML = `<img src="./charts/chart_3_compression_thresholds.svg" alt="Compression Thresholds Chart">`;

    const filtered = data.compression_sweep.filter((r) => r.zlib_level === compLevel);
    const rows = filtered.map((r) => `
      <tr>
        <td>${r.engine}</td>
        <td class="num">${r.threshold === -1 ? "Off (-1)" : r.threshold + " B"}</td>
        <td class="num">${r.wire_bytes.toLocaleString("en-US")} B</td>
        <td class="num" style="color:var(--accent);">${r.saved_pct.toFixed(2)}%</td>
        <td class="num">${r.throughput_mbps.toFixed(1)} Mbps</td>
        <td class="num">${r.cfb8_verified ? "OK" : "FAIL"}</td>
      </tr>
    `).join("");

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
        <tbody>${rows}</tbody>
      </table>
    `;
  }

  function renderLive() {
    const live = data.live_telemetry;
    controlsEl.innerHTML = `
      <span style="color:var(--muted);font-size:13.5px;">Paper 26.3 (roxaura.mcsh.gg) · ${live.rows.toLocaleString("en-US")} events · ${live.preempted_cnt.toLocaleString("en-US")} preemptions · ${live.saved_pct.toFixed(1)}% saved</span>
    `;
    chartEl.innerHTML = `<img src="./charts/chart_4_live_roxaura_telemetry.svg" alt="Live Paper 26.3 Telemetry Chart">`;

    const rows = Object.entries(live.phases).map(([phase, s]) => `
      <tr>
        <td><code class="mono">${phase}</code></td>
        <td class="num">${s.ka_n}</td>
        <td class="num">${s.ka_p50_ms.toFixed(1)} ms</td>
        <td class="num">${s.ka_p95_ms.toFixed(1)} ms</td>
        <td class="num">${s.mc_ping_p50_ms !== null ? s.mc_ping_p50_ms.toFixed(1) + " ms" : "—"}</td>
      </tr>
    `).join("");

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
        <tbody>${rows}</tbody>
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

  render();
})();
