# 1. Project

## From Gradle to the Kotlin Toolchain

The original is a Gradle 9 build (`build.gradle.kts`, `settings.gradle.kts`, wrapper) with
two modules, `sbf` and `cli`. The new project uses the **Kotlin Toolchain** (JetBrains'
declarative build, formerly *Amper*). The build is described in YAML, with no build scripts:

| Gradle (original) | Kotlin Toolchain (new) |
|---|---|
| `settings.gradle.kts` `include(":sbf", ":cli")` | `project.yaml` `modules:` list |
| `sbf/build.gradle.kts` (`kotlin("jvm")`, `java-library`, `java-test-fixtures`) | `sbf/module.yaml` `product: kmp/lib`, `platforms: [jvm, android]` |
| `kotlin { jvmToolchain(21) }` in each module | `common.module-template.yaml` (`settings.jvm.jdk.version: 21`), applied by every module |
| `src/main/kotlin`, `src/test/kotlin` | `src/`, `test/` (and `src@jvm/`, `src@android/` for platform code) |
| `testFixtures(project(":sbf"))` (SbfEncoder) | separate `testkit` module, used as a `test-dependency` |
| `./gradlew test` | `./kotlin test` |

Only the **library** was migrated. The `cli` module (`sbf2asc` tool) is out of scope; it keeps
working against the original library.

## Layout

```
mobile/libs/sbf/
├── project.yaml                     modules
├── common.module-template.yaml      shared settings (Kotlin 2.4, JDK 21)
├── sbf/                             THE LIBRARY  (kmp/lib: jvm + android)
│   ├── module.yaml
│   ├── src/ruggedmobile/sbf/        common code: reader, decoders, collector, epoch model
│   ├── src@jvm/, src@android/       InputStream.asByteSource() (one copy each)
│   └── test/ruggedmobile/sbf/       unit tests (run on JVM and Android)
├── testkit/                         SBF block encoder for tests (own CRC, no shared code)
├── reference/                       verbatim copy of the original library (package `sbf`)
├── reference-records/               verbatim copy of gnss-plot's SbfRecords decoders (test baseline)
├── verify/                          parity / allocation / memory / speed tests (JVM)
├── bench/                           benchmark app (JVM)
└── docs/migration/                  this documentation
```

The new library is in package **`ruggedmobile.sbf`**. The reference copy keeps the original
package `sbf`, so both can be loaded in the same JVM and compared directly.

## Platforms

`sbf` is a Kotlin Multiplatform library for `jvm` and `android`. All logic is in common code
(`src/`) and uses only the Kotlin standard library. The original used
`java.lang.Integer.bitCount` / `numberOfTrailingZeros`, which became `countOneBits()` /
`countTrailingZeroBits()`. The one-line `InputStream` adapter has a copy in `src@jvm/` and one
in `src@android/`. A `jvmAndAndroid` alias covering both would be the same set of platforms as
common code, and Toolchain 0.13 then fails to resolve common declarations in it, so the alias
is not used. Other targets (iOS, native) could be added later without touching the decoders.

Toolchain notes (0.13.0): `aliases:` is a top-level key of `module.yaml`, not a key under
`product:`. The `modules:` list in `project.yaml` is kept sorted, as the Toolchain recommends.
`.gitattributes` is copied from what `kotlin init` generates: the extension-less `kotlin` wrapper
must keep LF line endings, even on Windows with `core.autocrlf=true`, or it breaks under bash and
WSL. The wrapper scripts are committed, as in generated projects, so everyone builds with the same
Toolchain version (0.13.0, pinned by checksum in the scripts).

## Publishing

The Toolchain does not allow module dependencies outside a project's root, so other projects
(the plot, GNSS and app projects next to this one) use the library through Maven local.
`sbf/module.yaml` enables publishing:

```yaml
settings:
  publishing:
    enabled: true
    group: za.co.ruggedmobile
    version: 0.1.0

repositories:
  - url: mavenLocal
    publish: true
```

Publish after changing the library:

```bash
./kotlin publish mavenLocal -m sbf
```

This writes `za.co.ruggedmobile:sbf:0.1.0` to `~/.m2/repository`: a Gradle module file that
selects `sbf-jvm` (JAR) or `sbf-android` (AAR) for the consumer's platform. A consumer adds:

```yaml
repositories:
  - mavenLocal

dependencies:
  - za.co.ruggedmobile:sbf:0.1.0
```

Checked with a scratch project on Toolchain 0.13.0: a `jvm/app` runs against it, an
`android/app` packages it into its APK, and a local plugin (`jvm/amper-plugin`) can depend on a
Maven-local artifact too. Publishing is a Toolchain preview feature. `testkit`, `reference*`,
`verify` and `bench` are not published.

## Build and test

The project uses the Toolchain's checked-in wrapper (`kotlin` / `kotlin.bat` in the project
root). Run everything from the project root:

```bash
./kotlin build
```

```bash
./kotlin test
```

```bash
./kotlin test -m sbf
```

```bash
./kotlin test -m verify
```

```bash
./kotlin run -m bench -- --rounds 7 --csv bench-results/latest.csv
```

`verify` and `bench` read the real logs from `SBF_DATA_DIR`, or by default from
`../../../demos/SBF/data` relative to the project root (`D:\source\work\RuggedMobile\demos\SBF\data`).
Tests that need a missing log are skipped, not failed.

Tunable system properties for `verify` (defaults in brackets): `sbf.data.dir`,
`sbf.parity.maxBytes` [256 MiB: largest log in the parity run; raise it to include the 1.1 GB
`SupP1851.26_`], `sbf.fuzz.seeds` [40], `sbf.speed.rounds` [7], `sbf.soak.passes` [40].
