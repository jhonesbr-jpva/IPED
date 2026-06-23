# Implementation Plan: Upgrade Apache Tika to 3.3.1

**Branch**: `tika3-upgrade` | **Date**: 2026-06-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-tika3-upgrade/spec.md`

## Summary

Upgrade IPED's content-detection/extraction engine from Apache Tika **2.4.0 / 2.4.0-p1** to **3.3.1** as a behavioral-parity (maintenance) upgrade — no loss of supported formats, no case/index incompatibility, and a bounded performance budget. The major-version jump removes deprecated APIs IPED depends on (notably `AbstractParser`, used by 76 classes, and relocated metadata/IO symbols across ~102 files) and pulls newer transitive libraries (chiefly PDFBox 2→3). The approach: capture a 2.4.0 baseline, retire the obsolete `-p1` patched forks in favor of vanilla 3.3.1, migrate the API surface module-by-module bottom-up with a thin compatibility shim to localize churn, align shared dependencies "up" to Tika's expected versions, and gate the result on the existing test suite plus a curated multi-format benchmark case. Full research and decisions: [research.md](./research.md).

## Technical Context

**Language/Version**: Java 11 (`maven.compiler.source/target = 11`), UTF-8 source — unchanged by this feature.

**Primary Dependencies**: Apache Tika `2.4.0` → **`3.3.1`** (`tika-core`, `tika-parsers-standard-package`, `tika-parser-sqlite3-module`, `tika-parser-nlp-module`, `tika-langdetect-optimaize`); Apache PDFBox `2.0.27` → **3.0.x** — exact version pinned from the Tika 3.3.1 managed set in T004/T005 (D6, ⚠ VERIFY; not left as a floating range); Apache POI / commons-* aligned to the Tika 3.3.1 managed set. Lucene 9.2.0, Sleuthkit, LibreOffice UNO, Neo4j, Jersey — **unchanged**.

**Storage**: N/A (no datastore change). Existing Lucene case indexes MUST remain readable (FR-004, SC-005).

**Testing**: Existing JUnit 4 suites per module (primary regression gate, FR-006) + a curated multi-format benchmark case (parity SC-002, performance SC-007). `mvn -pl <module> -am test`.

**Target Platform**: Cross-platform desktop/CLI (Windows + Linux), JRE-embedded release. Unchanged.

**Project Type**: Multi-module Maven Java application (8 modules). Not web/mobile.

**Performance Goals**: No more than **10%** throughput regression and **10%** peak-memory increase vs. the pre-upgrade baseline on the benchmark case (SC-007).

**Constraints**: Behavioral parity — ≥99% of benchmark items keep equivalent extracted text & metadata, remainder triaged (SC-002); zero supported-format regressions (SC-001); no change to Lucene field keys / `AppAnalyzer` behavior; explicit recorded disposition for every `-p1` patch and Tika workaround (FR-007, FR-010, SC-006).

**Scale/Scope**: 287 files touch `org.apache.tika.*`; 76 `extends AbstractParser`; ~102 files (~515 refs) use relocated metadata/IO symbols (`MetadataUtil.java` = 54). Affected modules: `iped-api`, `iped-carvers`, `iped-parsers`, `iped-engine`, `iped-viewers`, `iped-geo`, `iped-app`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the **unpopulated template** (placeholder principles only) — no ratified, project-specific gates are defined. No constitutional constraints to evaluate or violate.

In place of formal gates, this plan honors the **CLAUDE.md guardrails** as de-facto governance:

| Guardrail (CLAUDE.md §12 / iped-engine §18) | Status |
|---|---|
| Do not rename Lucene field-key strings (`BasicProps`/`IndexItem`) | **Honored** — D7 adds mappings to preserve field names |
| Do not change `AppAnalyzer` fold/ASCII/lowercase | **Honored** — out of scope |
| Do not touch `Manager`/`Worker`/`ProcessingQueues`/`IndexWriter` concurrency invariants | **Honored** — Tika migration does not alter pipeline orchestration |
| Charset always explicit; UTF-8 default | **Honored** — no charset changes introduced |
| Prefer adding over modifying public `iped-api` interfaces | **Partial/justified** — `iped-api` consumes `tika-core` types (`Metadata`, `MediaType`, `Property`) in `IItem`/`ExtraProperties`/`MediaTypes`; their package may shift with Tika 3.x. Tracked in Complexity Tracking. |

**Result**: PASS (no constitution gates; guardrails satisfied or justified).

## Project Structure

### Documentation (this feature)

```text
specs/001-tika3-upgrade/
├── plan.md              # This file (/speckit-plan output)
├── spec.md              # Feature spec (+ Clarifications)
├── research.md          # Phase 0 output — decisions D1–D8, risk register
├── data-model.md        # Phase 1 output — Tika artifact & dependency inventory
├── quickstart.md        # Phase 1 output — baseline + validation guide
├── contracts/           # Phase 1 output — invariants that must NOT break
│   ├── parser-spi.md            # Parser SPI / AbstractParser migration contract
│   └── metadata-field-mapping.md # Tika metadata → Lucene field stability contract
└── checklists/
    └── requirements.md  # Spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

Affected areas of the existing multi-module tree (no new modules created):

```text
pom.xml                                   # tika.version 2.4.0→3.3.1; drop tika.core.version; pdfbox 2.0.27→3.x
iped-api/
└── src/main/java/iped/
    ├── properties/{MediaTypes,ExtraProperties}.java   # tika.mime / tika.metadata imports
    └── data/{IItem,IItemReader}.java                  # tika.metadata / tika.mime / tika.io imports
iped-carvers/iped-carvers-api/            # tika-core (MediaType, CarverType)
iped-parsers/iped-parsers-impl/
├── pom.xml                               # retire tika-* -p1; standard-package/module versions → 3.3.1
└── src/main/java/iped/parsers/
    ├── util/{AbstractParser shim,MetadataUtil.java}   # NEW shim (D4); 54 metadata-key refs (D5)
    ├── **/*.java                                       # 76 `extends AbstractParser` → shim import (D4)
    └── misc/PDFTextParser.java                          # PDFBox 2→3 direct API (D6)
iped-engine/
├── pom.xml                               # tika-parser-nlp-module, tika-langdetect-optimaize → 3.3.1
└── src/main/java/iped/engine/
    ├── tika/SyncMetadata.java            # revert/keep per TIKA-4126 (D3)
    ├── task/{ParsingTask,SignatureTask,index/IndexItem}.java
    └── io/{ParsingReader,ParsingProcess}.java
iped-viewers/iped-viewers-impl/           # EmailViewer/MetadataViewer (tika.metadata); PDFBox-based viewers (D6)
iped-geo/                                 # GeofileParser extends AbstractParser (D4)
iped-app/                                 # final packaging; classpath assembly (Bootstrap)
```

**Structure Decision**: Reuse the existing 8-module Maven layout; this is a cross-cutting dependency/API migration, not a feature addition, so **no new modules, packages, tasks, parsers, or Configurables are introduced**. The only net-new code is an internal `AbstractParser` compatibility shim in `iped-parsers-impl` (D4) whose sole purpose is to minimize and localize the 76-file diff. Work proceeds bottom-up along the module dependency order (D8).

## Complexity Tracking

> Only rows that deviate from "add, don't modify" guidance (CLAUDE.md §4/§12).

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Editing `iped-api` public types that expose `tika-core` classes (`IItem`, `ExtraProperties`, `MediaTypes`) | Tika 3.x may relocate/alter `Metadata`/`MediaType`/`Property`; `iped-api` re-exports these, so the change is unavoidable to compile | Cannot "add a new interface" — the existing public signatures reference Tika types directly; a parallel API would fork every consumer |
| Modifying 76 existing parser classes (`extends AbstractParser`) | `AbstractParser` is removed upstream; every subclass must change its base | A shim reduces each edit to a single import line, but the files must still be touched — there is no zero-edit path |
| Bumping IPED's directly-pinned PDFBox (and reviewing POI/commons) rather than isolating Tika | Clarified "align up" decision (spec 2026-06-23) + Tika 3.x runtime needs PDFBox 3.x | Holding old pins risks `LinkageError` at runtime (R1); shading/isolating Tika's PDFBox would double the PDFBox footprint and break IPED code that shares those types |
| Reverting/altering `SyncMetadata` (extends Tika `Metadata`) | Tied to TIKA-4126; `Metadata` API may change signatures | Leaving it untouched risks compile failure if overridden methods changed, and retains lock contention the upgrade is meant to remove |

## Phase Outputs

- **Phase 0** → [research.md](./research.md): Decisions D1–D8, measured footprint, risk register R1–R7, all unknowns resolved (residual items are implementation-time `⚠ VERIFY` confirmations).
- **Phase 1** → [data-model.md](./data-model.md) (artifact/dependency inventory + state model), [contracts/](./contracts/) (Parser SPI + metadata-field stability invariants), [quickstart.md](./quickstart.md) (baseline capture + validation procedure), and the agent-context update (CLAUDE.md SPECKIT marker → this plan).

## Next Command

`/speckit-tasks` — generate the dependency-ordered task list from these artifacts.
