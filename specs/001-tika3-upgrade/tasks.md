---
description: "Task list for Upgrade Apache Tika to 3.3.1 (rebalanced post-T004: PDFBox-first)"
---

# Tasks: Upgrade Apache Tika to 3.3.1

**Input**: Design documents from `specs/001-tika3-upgrade/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md) (esp. **§5 verification**), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Migration — the existing JUnit suites are the regression gate (FR-006); no new TDD tasks. US2 migrates the existing tests to green.

**🔁 Rebalanced (2026-06-23, post-T004)**: Verification ([research.md §5](./research.md)) nullified the feared Tika-API cohorts — `AbstractParser` is present-but-deprecated (76 files compile unchanged), and IPED's metadata symbols still exist. **The critical path is PDFBox 2.0.27 → 3.0.7** (incl. the `iped-app` timeline cache that needs the new `pdfbox-io` module) plus POI → 5.5.1. The `AbstractParser` shim is demoted to optional cleanup (Polish).

**Build env**: `JAVA_HOME=H:\java\LibericaJDK-11-Full` (JDK 11 + JavaFX). **Validation**: input `E:\hds\RockPi4\RockPi4.E01` → output `F:\smoke-tests\tika331`, compared to reference `F:\test_iped_estavel`.

## ✅ Implementation Progress (updated 2026-06-23)

**Foundational phase COMPLETE — full reactor compiles + assembles release on JDK 11** (`mvn clean install` = BUILD SUCCESS, 15 modules). Done: T002, T004–T016, T026, T027, T029, T030, T036 (commits on branch `tika3-upgrade`: `1bd0891e7`, `1114cdc60`, `34d1fb1d4`, `2ae70eac8`).

The actual compile-clean migration was **broader than the planned PDFBox/POI** — extra Tika 3.x breakages surfaced by the build and fixed under T006/T013:
- `HtmlParser` → `JSoupParser` (tagsoup→jsoup; 6 files) + re-added `tagsoup 1.2.1` dep
- `javax.xml.bind` removed from Tika 3.x classpath → re-added `jakarta.xml.bind-api 2.3.3` + `jaxb-runtime`
- `TikaInputStream.get(is, tmp)` → `get(is, tmp, new Metadata())` (~37 call sites)
- removed the obsolete `tika-parser-image-module` exclusion (IPED code needs `o.a.t.parser.image`)
- WhatsAppParser: dropped xerces-internal `MalformedByteSequenceException` import
- **BouncyCastle convergence** (T027): release had 4 conflicting BC jars (jdk15on 1.69/1.70 + jdk18on 1.83/1.84) → converged to a single **jdk18on 1.84** via root `dependencyManagement` + excluding `org.bouncycastle:*` from minio/neo4j/icepdf-viewer; CertificateParser `getObject()`→`getBaseObject().toASN1Primitive()`
- **AbstractParser fully removed** (T036): per decision, all 76 classes migrated `extends AbstractParser` → `implements Parser` (not a shim)
- `-p1` forks (T029/T030): dropped to vanilla 3.3.1 (the `2.4.0-p1` patches were the obsolete "while tika 2.4.2 is not released" workaround)

**REMAINING**: T001 (snapshot reference), T017–T025 (US1 parity validation + US2 tests), T028 (confirm TIKA-4126 → revert `SyncMetadata`?), T031 (disposition ledger), T032–T035 (polish docs). T003 is **moot** (the `-p1` patch deltas were obsolete; dropped without needing recovery).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no incomplete-task dependency)
- **[Story]**: US1 / US2 / US3 (Setup, Foundational, Polish carry none)
- Paths repo-relative from `H:\java\workspaces\workspace-iped\IPED-tika3-upgrade\`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the baseline reference and resolve remaining unknowns. No production code changes.

- [ ] T001 **Snapshot the existing 2.4.0 reference** `F:\test_iped_estavel` (already processed by the stable release): record item/subitem counts (overall + per category), the indexed field-key set, and text/metadata samples into `specs/001-tika3-upgrade/baseline/` (quickstart Step 0; references for SC-001/002/003/005/007). *Optional rigor*: also process `E:\hds\RockPi4\RockPi4.E01` with the current **unchanged** repo build for a strict Tika-only baseline.
- [X] T002 [P] Confirm validation assets accessible and define the comparison field set: input `E:\hds\RockPi4\RockPi4.E01`, reference `F:\test_iped_estavel`, output `F:\smoke-tests\tika331` (spec Clarification 2026-06-23)
- [ ] T003 [P] Recover the source delta of `tika-core 2.4.0-p1` and `tika-parsers-standard-package 2.4.0-p1` vs. upstream `2.4.0` from the `iped-maven` GitLab repo; record each patched change in `research.md` (D2, risk R4; feeds US3)
- [X] T004 [P] Verify Tika 3.3.1 coordinates/versions/API — **DONE, see [research.md §5](./research.md)**: 5 module coordinates exist; PDFBox **3.0.7**, POI **5.5.1**, commons-io 2.22.0, commons-compress 1.28.0, commons-lang3 3.20.0, metadata-extractor 2.20.0; AbstractParser present-deprecated (D4 nullified); `TikaCoreProperties.RESOURCE_NAME_KEY` present (D5 nullified). *Remaining*: TIKA-4126 fix version (confirmed in T028)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The compile-clean migration to Tika 3.3.1 — make every module build. **Blocks all user stories.** Bottom-up module order (D8). `JAVA_HOME` must point at JDK 11.

### Dependency & version changes

- [X] T005 Bump `pom.xml` properties: `tika.version` 2.4.0→**3.3.1**, remove `tika.core.version` (replace `${tika.core.version}` usages), `pdfbox.version` 2.0.27→**3.0.7** (D1, D6, research §5)
- [X] T006 Reconcile module POMs + add `dependencyManagement` at the verified versions: `iped-api/pom.xml`, `iped-carvers/iped-carvers-api/pom.xml`, `iped-parsers/iped-parsers-impl/pom.xml` (drop tika `-p1`; **remove the obsolete `tika-parser-image-module` exclusion**; let managed `metadata-extractor 2.20.0` apply), `iped-engine/pom.xml` (pdfbox/pdfbox-tools/xmpbox→3.0.7 **+ add `org.apache.pdfbox:pdfbox-io:3.0.7`**; tika nlp/langdetect→3.3.1); align POI **5.5.1**, commons-io/compress/lang3 (D2, D6, R5)

### PDFBox 2→3 migration — ⭐ CRITICAL PATH (different files → parallel)

- [X] T007 [P] PDFBox 2→3 in PDF rendering: `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/util/PDFToImage.java` and `.../util/PDFToThumb.java` (`PDDocument.load(...)`→`org.apache.pdfbox.Loader.loadPDF(...)`, `PDFRenderer`, `MemoryUsageSetting` changes) (D6, R1)
- [X] T008 [P] PDFBox 2→3 in viewer: `iped-viewers/iped-viewers-impl/src/main/java/iped/viewers/PDFBoxViewer.java` (D6, R1)
- [X] T009 [P] PDFBox **IO** 2→3 in the timeline cache: `iped-app/src/main/java/iped/app/timelinegraph/cache/persistance/CachePersistance.java`, `.../cache/TimeIndexedMap.java`, `.../datasets/IpedTimelineDataset.java` — migrate `org.apache.pdfbox.io.*` (RandomAccess*) usages to the new **`pdfbox-io`** module API (D6, R1)

### POI alignment & SyncMetadata (different files → parallel)

- [X] T010 [P] POI 5.5.1 review/align across 9 files: `iped-parsers .../misc/{GenericOLEParser,OFCParser,OFXParser}.java`, `.../mail/RFC822Parser.java`, `.../shareaza/MFCParser.java`, `.../discord/cache/Index.java`, `iped-viewers .../{EmailViewer,MsgViewer}.java`, `iped-engine .../util/Util.java` (D6)
- [X] T011 [P] Make `SyncMetadata` compile against the 3.3.1 `Metadata` API in `iped-engine/src/main/java/iped/engine/tika/SyncMetadata.java` (compile-only; revert/keep disposition in T028) (D3)

### Bottom-up compile gate (sequential, dependency order)

- [X] T012 Compile `iped-api`, `iped-utils`, `iped-carvers`: `mvn -pl iped-api,iped-utils,iped-carvers/iped-carvers-api,iped-carvers/iped-carvers-impl -am install` (D8)
- [X] T013 Compile `iped-parsers/iped-parsers-impl` (depends on T007, T010, T011)
- [X] T014 Compile `iped-viewers` and `iped-geo` (depends on T008)
- [X] T015 Compile `iped-engine` — `task/ParsingTask.java`, `task/SignatureTask.java`, `task/index/IndexItem.java`, `io/ParsingReader.java`, `io/ParsingProcess.java`, `webapi/Text.java` (depends on T011)
- [X] T016 Full `mvn clean install` compiles `iped-app` (depends on T009) and produces `target/release/iped-<version>/` (build gate; FR-005)

**Checkpoint**: Project compiles and installs on Tika 3.3.1 — acceptance slices can begin.

---

## Phase 3: User Story 1 - Evidence processing produces equivalent results (Priority: P1) 🎯 MVP

**Goal**: The upgraded build processes the evidence with no regression vs. the reference — same format coverage, valid config, equivalent text/metadata, old case still searchable, performance within budget.

**Independent Test**: Process `E:\hds\RockPi4\RockPi4.E01` into `F:\smoke-tests\tika331` and diff against `F:\test_iped_estavel`: format set ⊇ reference, top-level counts exact + subitem counts within ±1%, ≥99% items equivalent (rest justified), errors ≤ reference, the reference case opens/searches in the upgraded UI, throughput & peak memory within ≤10%.

- [ ] T017 [US1] Implement the **Lucene field-stability guard** so any Tika 3.x-renamed metadata property still maps to the existing field key, in `iped-parsers/iped-parsers-impl/.../util/MetadataUtil.java` and `iped-engine/.../task/index/IndexItem.java` ([contracts/metadata-field-mapping.md](./contracts/metadata-field-mapping.md), D7, FR-004)
- [ ] T018 [US1] **Config-validity check** — load the upgraded build and verify Tika-referencing config resolves under the 3.x model: `iped-app/resources/config/conf/CustomSignatures.xml`, MIME configs, `CategoriesConfig.json`, parser/signature config referencing Tika types (FR-008; spec Edge Case "Custom configuration validity")
- [ ] T019 [US1] Process `E:\hds\RockPi4\RockPi4.E01` with the upgraded build into `F:\smoke-tests\tika331` and produce the **upgraded snapshot** (counts, text, metadata, field-key set, errors, timing, peak memory) (quickstart Step 4; depends on T017, T018)
- [ ] T020 [P] [US1] **Format-coverage diff** `F:\smoke-tests\tika331` vs. `F:\test_iped_estavel` — assert zero supported-format regressions; remap/re-add any module that dropped a format (SC-001, FR-002, R6)
- [ ] T021 [P] [US1] **Extraction-parity & count diff** vs. reference — top-level item counts exact, subitem counts within ±1%, ≥99% items equivalent text+metadata; triage and justify the <1% (SC-002, FR-003)
- [ ] T022 [P] [US1] **Backward-compatibility check** — open `F:\test_iped_estavel` in the upgraded `BootstrapUI`; assert indexed field-key set identical and queries still match; errors ≤ reference (SC-005, SC-003, FR-004)
- [ ] T023 [P] [US1] **Performance comparison** — throughput regression ≤10% and peak-memory increase ≤10% vs. reference (SC-007)

**Checkpoint**: User Story 1 independently validated — MVP (the upgrade is forensically safe).

---

## Phase 4: User Story 2 - IPED builds, packages, and tests green on Tika 3.3.1 (Priority: P2)

**Goal**: Full build + existing test suite ≥ baseline pass rate + release package produced, clean dependency tree.

**Independent Test**: `mvn -B package` succeeds and the test pass rate is ≥ the pre-upgrade baseline, with every remaining failure explained and pre-existing.

- [ ] T024 [US2] Migrate **test sources** broken by Tika 3.x / PDFBox 3 / POI 5.5.1 API changes to the new APIs, preserving their original assertions — incl. `iped-parsers/.../test/.../AbstractPkgTest.java` helpers and PDF/POI-touching parser tests (FR-006, [contracts/parser-spi.md](./contracts/parser-spi.md))
- [ ] T025 [US2] Run `mvn -pl iped-parsers/iped-parsers-impl test` and `mvn -pl iped-engine test`; resolve runtime failures until pass rate ≥ baseline (SC-004; depends on T024)
- [X] T026 [P] [US2] Run `mvn -B package --file pom.xml` (CI-equivalent) and confirm the release artifact builds without Tika/PDFBox errors (FR-005, quickstart Step 2)
- [X] T027 [P] [US2] **Dependency-tree audit** — `mvn dependency:tree` shows single Tika **3.3.1**, PDFBox **3.0.7** (+ pdfbox-io), POI **5.5.1**, aligned commons; no conflicts affecting Lucene/webapi/other consumers (FR-009, R5, quickstart Step 3)

**Checkpoint**: User Story 2 independently validated — buildable and shippable.

---

## Phase 5: User Story 3 - IPED-specific Tika patches and workarounds are reconciled (Priority: P3)

**Goal**: Every `-p1` fork and Tika workaround has an intentional, recorded disposition.

**Independent Test**: Walk the disposition ledger — each Patched Fork and Workaround from [data-model.md](./data-model.md) is kept-with-justification or removed-because-fixed; none undecided.

- [ ] T028 [US3] Finalize **SyncMetadata / TIKA-4126**: confirm the fix version in 3.3.1; if fixed, revert commit `b673cf4` and route callers back to plain `Metadata`; else keep with recorded reason. Update the comment at `pom.xml:33` either way (D3, FR-007, SC-006)
- [X] T029 [P] [US3] Finalize **`tika-core` `-p1`** disposition — default drop-to-vanilla 3.3.1; re-fork only if a still-needed patch is absent upstream (per T003) (D2, FR-010)
- [X] T030 [P] [US3] Finalize **`tika-parsers-standard-package` `-p1`** disposition — drop the obsolete fork for vanilla 3.3.1; the stock-image-module exclusion is **confirmed obsolete** (research §5) and `metadata-extractor` is handled via the managed 2.20.0 version (D2, FR-007)
- [ ] T031 [US3] Record the **disposition ledger** (every Patched Fork + Workaround → outcome + rationale) in `research.md` (SC-006; depends on T028, T029, T030)

**Checkpoint**: User Story 3 independently validated — no obsolete forks or undecided workarounds.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T032 [P] Update dependency tables: `CLAUDE.md` (root §3: Tika 3.3.1, PDFBox 3.0.7), `iped-engine/CLAUDE.md` (§1, §14: Tika/PDFBox/POI), `iped-parsers/CLAUDE.md`
- [ ] T033 [P] Update `ThirdParty.txt` and `licenses/` for changed versions (Tika 3.3.1, PDFBox 3.0.7, POI 5.5.1, commons, metadata-extractor)
- [ ] T034 [P] Add a `ReleaseNotes.txt` entry for the Tika 3.3.1 upgrade
- [ ] T035 Run the full [quickstart.md](./quickstart.md) validation end-to-end; attach results + disposition ledger to `specs/001-tika3-upgrade/` (confirms SC-001 … SC-007)
- [X] T036 [P] *(OPTIONAL — deprecation cleanup, off critical path)* Replace the deprecated `org.apache.tika.parser.AbstractParser` usage in the 76 subclasses with an internal base class ([contracts/parser-spi.md](./contracts/parser-spi.md), research §5) — only if the team wants to clear the deprecation warnings

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: start immediately. T004 already done.
- **Foundational (Phase 2)**: needs T003 (patch delta) for the US3 input but compiles independently; **blocks all user stories**.
- **User Stories (Phase 3–5)**: all depend on the T016 build gate; mutually **independent** acceptance slices (parallel or P1→P2→P3).
- **Polish (Phase 6)**: after desired stories complete.

### Within Foundational (critical ordering)

- T005 → T006 (versions before POM reconciliation)
- After T006: **T007, T008, T009 (PDFBox), T010 (POI), T011 (SyncMetadata)** run in parallel (different files)
- Compile gates strictly sequential bottom-up: T012 → T013 → T014 → T015 → T016 (each depends on the relevant migration tasks above)

### Within User Story 1

- T017 (guard) + T018 (config) → T019 (process) → T020, T021, T022, T023 (independent diffs/checks)

### User story dependencies

- **US1 (P1)**, **US2 (P2)**, **US3 (P3)**: each needs only Foundational (US3 also needs T003). Independent of each other.

---

## Parallel Opportunities

- **Setup**: T002, T003 in parallel (T004 done; T001 snapshots the existing reference).
- **Foundational**: T007/T008/T009/T010/T011 in parallel after T006, before the sequential compile gates.
- **US1**: after T019 — T020, T021, T022, T023 all [P].
- **US2**: T026, T027 parallel with the T024→T025 chain.
- **US3**: T029, T030 parallel; T031 after.
- **Polish**: T032, T033, T034, T036 parallel; T035 last.

### Parallel example — Foundational PDFBox/POI migration

```bash
export JAVA_HOME='/h/java/LibericaJDK-11-Full'
# After T005/T006, migrate in parallel (different files):
Task: "PDFBox 2->3 PDF rendering: PDFToImage.java + PDFToThumb.java (T007)"
Task: "PDFBox 2->3 viewer: PDFBoxViewer.java (T008)"
Task: "PDFBox-io 2->3 timeline cache: CachePersistance/TimeIndexedMap/IpedTimelineDataset (T009)"
Task: "POI 5.5.1 review across 9 files (T010)"
Task: "SyncMetadata compile vs 3.3.1 Metadata (T011)"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Setup (baseline reference already exists — just snapshot it).
2. Foundational — version bump + **PDFBox 2→3** + POI + bottom-up compile to a working release.
3. US1 — process `RockPi4.E01` → `F:\smoke-tests\tika331`, diff vs `F:\test_iped_estavel`.
4. **STOP and VALIDATE**: zero format regressions, valid config, ≥99% parity, old case opens, ≤10% perf → forensically safe MVP.

### Incremental Delivery

Setup+Foundational (builds) → US1 (parity, MVP) → US2 (tests green + package) → US3 (patch ledger) → Polish (docs + optional shim).

---

## Requirement Coverage

| Requirement | Task(s) |
|---|---|
| FR-001 use Tika 3.3.1 | T005, T006 |
| FR-002 no format-coverage loss | T020 |
| FR-003 custom parsers equivalent | T021, T024 |
| FR-004 field-key/metadata stability | T017, T022 |
| FR-005 build/package green | T016, T026 |
| FR-006 tests pass ≥ baseline | T024, T025 |
| FR-007 workarounds reconciled | T028, T030, T031 |
| FR-008 Tika config validity | T018 |
| FR-009 align-up shared deps (PDFBox/POI/commons) | T006, T007, T008, T009, T010, T027 |
| FR-010 record fork decision | T029, T031 |
| SC-001 format set ⊇ baseline | T020 |
| SC-002 ≥99% parity + count tolerance | T021 |
| SC-003 errors ≤ baseline | T022 |
| SC-004 test pass ≥ baseline | T025 |
| SC-005 release runs; old case opens | T022, T026 |
| SC-006 every workaround recorded | T031 |
| SC-007 ≤10% throughput/memory | T001, T023 |

---

## Notes

- **Critical path = PDFBox 2→3** (T007–T009): `Loader.loadPDF`, `PDFRenderer`, and the timeline-cache IO move to the new `pdfbox-io` module are the genuine breaking changes. Everything Tika-API-side compiles after the version bump (research §5).
- **AbstractParser shim and metadata-symbol migration are NOT in the critical path** — the classes/symbols still exist in 3.3.1. The optional deprecation cleanup is T036.
- `JAVA_HOME` MUST be `H:\java\LibericaJDK-11-Full` for every build (default JDK 18/25 lacks JavaFX).
- Do **not** rename any Lucene field key or change `AppAnalyzer` behavior; the T017 guard enforces this.
- Migrated tests must keep their **original** assertions — do not weaken to pass (FR-006).
