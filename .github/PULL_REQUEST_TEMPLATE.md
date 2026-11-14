## Summary

<!-- What does this PR change and why? -->

Closes #<!-- issue number required by pr-guard -->

## Checklist

- [ ] Linked to an existing issue (`Closes #...`, `Fixes #...`, or `Resolves #...`)
- [ ] Net added lines stay within the 500-line PR cap (excluding `Cargo.lock` and benchmark CSVs)
- [ ] `cargo test --release` passes (if `rust-core/` changed)
- [ ] `gradle :universal-jar:test :universal-jar:jar` passes
- [ ] No protocol desync or vanilla client regressions introduced
