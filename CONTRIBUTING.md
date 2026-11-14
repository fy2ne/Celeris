# Contributing to Celeris

Celeris is a single-jar, server-side Netty transport and Rust JNI acceleration layer for Minecraft servers and proxies.

## Claiming an Issue

1. Pick an open issue (or open one first so design scope is agreed).
2. Comment `/claim` on the issue to acquire a **7-day exclusive lease**.
   - Comment `/unclaim` if you step away.
   - Inactive leases expire automatically after 7 days so issues stay unblocked.
3. Reference the issue in your pull request body (`Closes #<number>`, `Fixes #<number>`, or `Resolves #<number>`).

## Pull Request Rules

- **Linked issue required:** PRs without an issue reference are blocked by CI (`pr-guard.yml`).
- **500-line cap:** Keep PRs focused—maximum **500 added lines** per PR (excluding `Cargo.lock` and `docs/benchmarks/data/`). Split larger changes into stacked PRs.
- **Zero client-mod assumptions:** Every change must work with unmodified vanilla Minecraft clients and respect ViaVersion/ViaBackwards pipelines.
- **No fabricated numbers:** Benchmark updates must include raw CSV/JSON outputs from reproducible runs.

## Local Development

### Prerequisites
- Java 21+
- Rust stable toolchain (`cargo`, `rustc`)
- Gradle 9+ (or system Gradle)

### Build & Test
```bash
# Run Rust unit tests and build native library
cd rust-core
cargo test --release
cargo build --release
cd ..

# Build and test universal jar
gradle :universal-jar:test :universal-jar:jar
```

The built jar lands at `universal-jar/build/libs/Celeris-<version>.jar`.
