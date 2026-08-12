# Quickstart: Validating the Tika 3.3.1 Upgrade

**Feature**: [spec.md](../spec.md) · **Plan**: [plan.md](./plan.md) · **Date**: 2026-06-23

A runnable validation guide proving the upgrade meets its success criteria. Implementation steps live in `tasks.md`; this file is the **how-to-verify**. Run baseline capture **before** changing any code.

## Prerequisites

- **JDK 11 with JavaFX** = Liberica Full JDK 11 at **`H:\java\LibericaJDK-11-Full`**. Set it for every build:
  - PowerShell: `$env:JAVA_HOME = 'H:\java\LibericaJDK-11-Full'`
  - Git Bash: `export JAVA_HOME='/h/java/LibericaJDK-11-Full'`
  - (The default shell JDK is 18/25 with no JavaFX — builds of `iped-viewers`/`iped-geo`/`iped-app` will fail without overriding `JAVA_HOME`.)
- Maven 3.6+ (`H:\java\apache-maven-3.9.16`).
- Native tools per CI (`.github/workflows/maven.yml`) for the full test suite: `tesseract-ocr`, `imagemagick`, `pff-tools`, `libesedb-utils`, `python3` + `jep==4.0.3`, etc. A compile-and-unit-test pass does not require all of them.
- **Benchmark case**: **`F:\test_iped_estavel`** (the "stable" reference case) — covers the high-traffic categories for parity (SC-002) and performance (SC-007).

## Step 0 — Establish the 2.4.0 baseline reference

The baseline already exists — no need to reprocess for a reference:

- **Input evidence**: `E:\hds\RockPi4\RockPi4.E01` (EnCase E01, ~8.5 GB).
- **Reference case (processed by the stable released IPED)**: `F:\test_iped_estavel` (an IPED case: `iped/index`, `data`, `bookmarks.iped`, `htmlreport`, `sleuth.db`).

```bash
# Snapshot the reference's measurable facts for later diffing:
#   - item/subitem counts (overall + per category)   - indexed field-key set
#   - extracted-text / metadata samples               - (timing/memory: from its IPED-SearchApp.log if available)
# Save under: specs/001-tika3-upgrade/baseline/
```

> **Rigor note**: `F:\test_iped_estavel` was produced by a stable *release*, not this repo's exact 2.4.0-SNAPSHOT. It is a sound **smoke-test** reference. For a strict *Tika-only* diff, optionally also process the **same** `RockPi4.E01` with the current **unchanged** repo build (JDK 11) and snapshot that as the true apples-to-apples baseline.

Record: total items/subitems, per-format counts, the **indexed field-key set**. These are the references for SC-001/002/003/005/007.

## Step 1 — Apply the upgrade & build bottom-up (D8)

```bash
export JAVA_HOME='/h/java/LibericaJDK-11-Full'
# Version bumps in pom.xml (tika 2.4.0→3.3.1, drop tika.core.version, pdfbox→3.0.7 + add pdfbox-io,
# poi→5.5.1) per research D1/D6/§5, then compile module-by-module along the dependency order:
mvn -pl iped-api -am clean install
mvn -pl iped-utils -am install
mvn -pl iped-carvers/iped-carvers-api -am install
mvn -pl iped-parsers/iped-parsers-impl -am install     # AbstractParser shim + metadata symbols here
mvn -pl iped-viewers/iped-viewers-impl -am install
mvn -pl iped-geo -am install
mvn -pl iped-engine -am install                         # SyncMetadata / ParsingTask here
mvn -pl iped-app -am install
```

**Expected**: each module compiles; the final `target/release/iped-<version>/` is produced (SC-004 build gate, FR-005).

## Step 2 — Test-suite parity (FR-006, SC-004)

```bash
mvn -pl iped-parsers/iped-parsers-impl test
mvn -pl iped-engine test
mvn -B package --file pom.xml          # as CI runs it
```

**Expected**: pass rate ≥ baseline. Any test changed only for a Tika 3.x API/package move keeps its original assertions ([contracts/parser-spi.md](./contracts/parser-spi.md)). No unexplained new failure.

## Step 3 — Dependency-tree sanity (FR-009, D6, R5)

```bash
mvn -pl iped-engine dependency:tree -Dincludes=org.apache.tika
mvn dependency:tree | grep -Ei "pdfbox|poi|commons-(io|compress|lang3)|tika"
```

**Expected**: a single Tika 3.3.1 line per artifact; PDFBox/POI/commons resolve to the Tika-3.3.1-aligned versions with no conflicting duplicates.

## Step 4 — Format-coverage & extraction parity (SC-001, SC-002)

Process the **input evidence** with the upgraded (3.3.1) build into a fresh output dir, then diff against the reference:

```bash
export JAVA_HOME='/h/java/LibericaJDK-11-Full'
# from the upgraded target/release/iped-<version>/:
#   iped.exe -d E:\hds\RockPi4\RockPi4.E01 -o F:\smoke-tests\tika331
```

Diff `F:\smoke-tests\tika331` against the reference `F:\test_iped_estavel`:

- **SC-001**: detected/parsed format set ⊇ reference — **zero** formats regress to unsupported.
- **SC-002**: top-level item counts exact, subitem counts within ±1%, ≥99% of items equivalent text & metadata; triage and justify the <1%.
- **SC-003**: processing error/failure count ≤ reference.

## Step 5 — Case backward-compatibility (FR-004, SC-005)

```bash
# Open the REFERENCE case (made by the stable release) in the UPGRADED analysis UI:
#   iped.app.bootstrap.BootstrapUI  -> point at F:\test_iped_estavel
```

**Expected**: the old case opens, searches, and displays correctly; the indexed field-key set matches ([contracts/metadata-field-mapping.md](./contracts/metadata-field-mapping.md)).

## Step 6 — Performance budget (SC-007)

Compare upgraded vs. baseline on the same benchmark + hardware:

- Throughput regression **≤ 10%**; peak-memory increase **≤ 10%**.

## Step 7 — Disposition ledger (FR-007, FR-010, SC-006)

Confirm a recorded outcome for each item in [data-model.md](./data-model.md):

- `tika-core` `-p1` → disposition recorded (default `drop-vanilla`, D2).
- `tika-parsers-standard-package` `-p1` (+ exclusions) → disposition recorded.
- `SyncMetadata` / TIKA-4126 → reverted or kept-with-reason (D3).
- `metadata-extractor` & stock-image-module exclusions → kept-via-Maven or dropped.

## Done

All of SC-001 … SC-007 verified against the captured baseline, with every parity/perf exception and every patch/workaround disposition recorded.
