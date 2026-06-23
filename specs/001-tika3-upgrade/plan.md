# Implementation Plan: Upgrade Apache Tika to 3.3.1

**Branch**: `tika3-upgrade` | **Date**: 2026-06-23 (refreshed post-T004) | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-tika3-upgrade/spec.md`

> **🔁 Refresh note (2026-06-23)**: This plan was rebalanced after the T004 verification ([research.md §5](./research.md)) and after the build toolchain + benchmark became available. The Tika-**API** migration the first plan feared (76-file `AbstractParser` shim, ~102-file metadata-symbol migration) is **nullified** — those compile unchanged on 3.3.1. The real work is the **transitive dependency align-up, dominated by PDFBox 2.0.27 → 3.0.7**.

## Summary

Upgrade IPED from Apache Tika **2.4.0 / 2.4.0-p1** to **3.3.1** as a behavioral-parity upgrade. Verification showed the Tika public API IPED uses is stable across the jump (`AbstractParser` still present, just `@Deprecated`; `TikaCoreProperties.RESOURCE_NAME_KEY` still present; `TikaMetadataKeys`/`tika.io.IOUtils` unused) — so after the version bump, the vast majority of the 287 Tika-referencing files compile unchanged. The substantive work is therefore: (1) bump POMs to Tika 3.3.1 + the transitive set it expects, retiring the now-obsolete `-p1` forks; (2) migrate IPED's **direct PDFBox 2→3 usage** (6 files, incl. the `iped-app` timeline cache that depends on PDFBox IO classes moved to the new `pdfbox-io` module) and review **POI 5.5.1** usage (9 files); (3) make `SyncMetadata` compile and decide the TIKA-4126 disposition; (4) validate parity/perf on the benchmark case with JDK 11. Full evidence and decisions: [research.md](./research.md).

## Technical Context

**Language/Version**: Java 11, UTF-8 source — unchanged. **Build JDK**: Liberica Full JDK 11 at `H:\java\LibericaJDK-11-Full` (set `JAVA_HOME` here; JavaFX bundled — required for `iped-viewers`/`iped-geo`/`iped-app`). Maven `H:\java\apache-maven-3.9.16`.

**Primary Dependencies (verified targets, T004)**: Apache Tika `2.4.0`→**`3.3.1`** (`tika-core`, `tika-parsers-standard-package`, `tika-parser-sqlite3-module`, `tika-parser-nlp-module`, `tika-langdetect-optimaize` — all coordinates confirmed). Align-up transitive set: **PDFBox 2.0.27→3.0.7** (`pdfbox`, `fontbox`, `xmpbox`, `pdfbox-tools`, **new `pdfbox-io`**), jbig2-imageio 3.0.4→3.0.5, **POI→5.5.1**, commons-io 2.22.0, commons-compress 1.27.1→1.28.0, commons-lang3 3.20.0, metadata-extractor 2.20.0. Lucene 9.2.0, Sleuthkit, LibreOffice UNO, Neo4j, Jersey — **unchanged**.

**Storage**: N/A. Existing Lucene case indexes MUST remain readable (FR-004, SC-005).

**Testing**: Existing JUnit 4 suites (regression gate, FR-006) + a smoke-test case for parity (SC-002) and performance (SC-007). **Input evidence**: `E:\hds\RockPi4\RockPi4.E01` (E01, ~8.5 GB). **Reference baseline (already processed by the stable release)**: `F:\test_iped_estavel`. **Upgraded output**: `F:\smoke-tests\tika331`. The reference already exists, so no pre-change reprocessing is needed for a baseline (optionally process the same E01 with the unchanged repo build for a strict Tika-only diff).

**Target Platform**: Windows + Linux desktop/CLI, JRE-embedded release. Unchanged.

**Project Type**: Multi-module Maven Java application (8 modules).

**Performance Goals**: ≤**10%** throughput regression and ≤**10%** peak-memory increase vs. baseline (SC-007).

**Constraints**: Behavioral parity — top-level item counts exact, subitem counts ±1%, ≥99% items equivalent text/metadata (SC-002); zero supported-format regressions (SC-001); no change to Lucene field keys / `AppAnalyzer`; explicit recorded disposition for every `-p1` fork and Tika workaround (FR-007/FR-010/SC-006).

**Scale/Scope (corrected post-T004)**:

| Cohort | First plan (worst case) | Verified reality | Real edit cost |
|---|---|---|---|
| `extends AbstractParser` | 76 files migrate (shim) | class present (deprecated) → **compile unchanged** | 0 (optional cleanup) |
| metadata/IO symbols | ~102 files migrate | `RESOURCE_NAME_KEY` present; others unused → **no change** | 0 |
| **PDFBox direct API** | "review" | **2→3 breaking; 6 files + `pdfbox-io` module** | **primary** |
| POI direct API | "review" | 5.2→**5.5.1**; 9 files to review | secondary |
| POMs | 5 | 5 (+ `pdfbox-io` dep, drop `-p1`/obsolete exclusions) | small |
| `SyncMetadata` | revert/keep | compile-check vs 3.3.1 `Metadata` | 1 file |

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` remains the unpopulated template — no ratified gates. CLAUDE.md guardrails serve as de-facto governance:

| Guardrail | Status |
|---|---|
| Do not rename Lucene field-key strings (`BasicProps`/`IndexItem`) | **Honored** — D7 field-stability guard preserves keys |
| Do not change `AppAnalyzer` behavior | **Honored** — out of scope |
| Do not touch `Manager`/`Worker`/`ProcessingQueues`/`IndexWriter` invariants | **Honored** — upgrade doesn't alter orchestration |
| Charset always explicit; UTF-8 | **Honored** |
| Prefer adding over modifying public `iped-api` interfaces | **Partial/justified** — `iped-api` re-exports `tika-core` types; verified these compile on 3.3.1, so the risk is lower than first assessed (Complexity Tracking) |

**Result**: PASS.

## Project Structure

### Documentation (this feature)

```text
specs/001-tika3-upgrade/
├── plan.md              # This file (refreshed)
├── spec.md              # Spec + Clarifications
├── research.md          # Decisions D1–D8 + §5 T004 verification (D4/D5 nullified, versions pinned)
├── data-model.md        # Migration-unit inventory (rebalanced to PDFBox)
├── quickstart.md        # Baseline + validation (JDK 11 + F:\test_iped_estavel)
├── contracts/
│   ├── parser-spi.md            # AbstractParser now present-deprecated (optional cleanup)
│   └── metadata-field-mapping.md # Lucene field-key stability (still the live invariant)
└── checklists/requirements.md
```

### Source Code (repository root) — focus areas

```text
pom.xml                                   # tika 2.4.0→3.3.1; drop tika.core.version; pdfbox 2.0.27→3.0.7
                                          #   add commons/poi/metadata-extractor alignment in dependencyManagement
iped-api/                                 # re-exports tika types (MediaTypes/ExtraProperties/IItem) — verify compile only
iped-parsers/iped-parsers-impl/
├── pom.xml                               # drop tika-* -p1; standard-package/modules → 3.3.1; remove obsolete image-module exclusion
└── src/main/java/iped/parsers/
    ├── util/PDFToImage.java              # ⭐ PDFBox 2→3 (PDDocument.load→Loader.loadPDF, PDFRenderer)
    ├── util/PDFToThumb.java              # ⭐ PDFBox 2→3
    ├── misc/{GenericOLEParser,OFCParser,OFXParser}.java, mail/RFC822Parser.java, shareaza/MFCParser.java, discord/cache/Index.java  # POI 5.5.1 review
    └── (76 AbstractParser subclasses compile unchanged — optional deprecation cleanup only)
iped-viewers/iped-viewers-impl/
├── PDFBoxViewer.java                     # ⭐ PDFBox 2→3
└── EmailViewer.java, MsgViewer.java      # POI 5.5.1 review
iped-engine/
├── pom.xml                               # pdfbox/pdfbox-tools/xmpbox → 3.0.7 (+ pdfbox-io); tika nlp/langdetect → 3.3.1
├── src/main/java/iped/engine/tika/SyncMetadata.java   # compile vs 3.3.1 Metadata; TIKA-4126 disposition
├── .../task/index/IndexItem.java         # D7 field-stability guard
└── .../util/Util.java                    # POI 5.5.1 review
iped-app/src/main/java/iped/app/timelinegraph/
├── cache/persistance/CachePersistance.java   # ⭐ PDFBox IO → new pdfbox-io module (org.apache.pdfbox.io.*)
├── cache/TimeIndexedMap.java                 # ⭐ PDFBox IO
└── datasets/IpedTimelineDataset.java         # ⭐ PDFBox IO
```

**Structure Decision**: Reuse the existing 8-module layout; no new modules/packages/tasks/parsers introduced. The previously-planned internal `AbstractParser` shim is **demoted to optional** (the class still exists upstream). The critical path is the **PDFBox 2→3 migration** across 6 files (including the timeline-cache IO usage that requires the new `pdfbox-io` artifact), plus POI 5.5.1 review across 9 files.

## Complexity Tracking

> Only rows that deviate from "add, don't modify" guidance. Rows for `AbstractParser` (76 files) and metadata symbols (~102 files) are **removed** — T004 proved they are not modifications at all.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Migrate IPED's direct **PDFBox 2→3** usage (6 files) + add `pdfbox-io` | Tika 3.3.1's PDF module requires PDFBox 3.0.7; IPED calls PDFBox APIs directly (PDF rendering + timeline-cache IO) that broke between 2 and 3 | Holding PDFBox 2.x forces `LinkageError` against Tika 3.x's PDF module (R1); the timeline cache uses IO classes that only exist in `pdfbox-io` 3.x |
| Review/align **POI 5.5.1** usage (9 files) | POI bumped with Tika 3.3.1; IPED uses POI directly in parsers/viewers/engine | Pinning old POI risks split-version conflicts on the shared classpath |
| Possibly edit `iped-api` tika-type re-exports | `iped-api` exposes `Metadata`/`MediaType`/`Property`; **verified these still resolve on 3.3.1**, so likely compile-only | A parallel API would fork every consumer for no benefit |
| Revert/alter `SyncMetadata` | Tied to TIKA-4126; `Metadata` may have changed method signatures | Leaving untouched risks compile failure and retains lock contention the upgrade should remove |

## Phase Outputs

- **Phase 0** → [research.md](./research.md): D1–D8 + **§5 verification** (artifact coordinates, pinned versions, D4/D5 nullified, image-module exclusion obsolete). No open `NEEDS CLARIFICATION`; only the TIKA-4126 fix-version confirmation remains (feeds T029).
- **Phase 1** → [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md), CLAUDE.md SPECKIT marker (already → this plan).

## Next Command

`/speckit-tasks` to regenerate the task list rebalanced to this plan (PDFBox-first; shim optional), **or** proceed with the existing `tasks.md` (already annotated post-T004: T004 done, T007–T009 demoted, T005/T006 pinned to 3.0.7). With JDK 11 now available, T001 (baseline on `F:\test_iped_estavel`) is unblocked.
