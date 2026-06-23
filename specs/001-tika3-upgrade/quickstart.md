# Quickstart: Validating the Tika 3.3.1 Upgrade

**Feature**: [spec.md](../spec.md) · **Plan**: [plan.md](./plan.md) · **Date**: 2026-06-23

A runnable validation guide proving the upgrade meets its success criteria. Implementation steps live in `tasks.md`; this file is the **how-to-verify**. Run baseline capture **before** changing any code.

## Prerequisites

- JDK 11 with JavaFX (Liberica Full JDK 11), `JAVA_HOME` set.
- Maven 3.6+.
- Native tools per CI (`.github/workflows/maven.yml`) for the full test suite: `tesseract-ocr`, `imagemagick`, `pff-tools`, `libesedb-utils`, `python3` + `jep==4.0.3`, etc. A compile-and-unit-test pass does not require all of them.
- The **curated multi-format benchmark case** (clarification 2026-06-23): documents, email, archives, images, SQLite/chat apps, registry, PDF, office. Assembled (non-sensitive) so it can be shared and re-run.

## Step 0 — Capture the 2.4.0 baseline (on `master`/pre-change)

```bash
# 1. Build current state + run tests, capture results
mvn -B clean install 2>&1 | tee baseline-build.log

# 2. Process the benchmark case and snapshot outputs for later diff
#    (item/subitem counts, extracted text, indexed metadata, field-key set, timing, peak memory)
#    Save under: specs/001-tika3-upgrade/baseline/
```

Record: total items/subitems, per-format counts, the **indexed field-key set**, wall-clock processing time, and peak memory. These are the references for SC-001/002/003/005/007.

## Step 1 — Apply the upgrade & build bottom-up (D8)

```bash
# Version bumps in pom.xml (tika 2.4.0→3.3.1, drop tika.core.version, pdfbox→3.x) per research D1/D6,
# then compile module-by-module along the dependency order:
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

Process the benchmark case on the upgraded build; diff against the Step 0 snapshot:

- **SC-001**: detected/parsed format set ⊇ baseline — **zero** formats regress to unsupported.
- **SC-002**: ≥99% of items have equivalent extracted text & metadata; triage and justify the <1%.
- **SC-003**: processing error/failure count ≤ baseline.

## Step 5 — Case backward-compatibility (FR-004, SC-005)

```bash
# Open the BASELINE case (processed on 2.4.0) in the UPGRADED analysis UI:
#   iped.app.bootstrap.BootstrapUI  -> point at specs/001-tika3-upgrade/baseline/<case>
```

**Expected**: the old case opens, searches, and displays correctly; the indexed field-key set matches the baseline ([contracts/metadata-field-mapping.md](./contracts/metadata-field-mapping.md)).

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
