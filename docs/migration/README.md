# Migration from sbf2asc-kotlin `sbf` to the data-oriented library

This folder documents how the `sbf` library from `demos/SBF/ns/sbf2asc-kotlin` was moved to a new
Kotlin Toolchain project with a data-oriented design, and how the result was verified.

| Document | Contents |
|----------|----------|
| [01-project.md](01-project.md) | New project layout, build tool (Kotlin Toolchain, formerly Amper), modules, how to build and test |
| [02-design.md](02-design.md) | The data-oriented design: what changed in each component, and why |
| [03-api-migration.md](03-api-migration.md) | Old API to new API, with before/after code |
| [04-verification.md](04-verification.md) | How functional parity, memory and speed are tested; thresholds; how to run |
| [05-results.md](05-results.md) | Measured results: parity, allocation, retained heap, GC, throughput |
| [06-behaviour.md](06-behaviour.md) | Behaviour kept on purpose, lifetime rules for reused objects, and the C bug fixes carried over |

## Summary

- **No change to the original project.** The original sources are copied, unchanged, into the
  `reference` module, where they serve only as the baseline for tests and benchmarks.
- **Same results.** For every block and every decoded measurement field, the new library returns
  values bit-identical to the original's. This is checked on every real log in
  `demos/SBF/data`, in both file mode and live mode, and on thousands of damaged inputs.
- **Constant memory.** Once warmed up, reading and decoding allocates no memory per block or per
  epoch, so a long-running app produces no garbage and its heap stays flat.
- **Not slower.** A test fails the build if the new library's median time on the same data is
  higher than the original's. See [05-results.md](05-results.md) for the measured numbers.
- **Kotlin Multiplatform.** Common code targets JVM and Android. The only platform code is the
  `InputStream` adapter.
