---
description: "Task list for Upgrade Apache Tika to 3.3.1"
---

# Tasks: Upgrade Apache Tika to 3.3.1

**Input**: Design documents from `specs/001-tika3-upgrade/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: This is a dependency/API migration. The spec mandates **preserving the existing** JUnit suites as the regression gate (FR-006) — it does **not** request new TDD tests. Therefore no "write failing tests first" tasks are generated; instead User Story 2 migrates the existing tests to green.

**Migration note**: Nothing is testable until the project compiles on Tika 3.3.1, so the compile-clean migration is **shared, blocking work** and lives in Phase 2 (Foundational). Phases 3–5 are independently-testable acceptance slices per the spec's user stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (Setup, Foundational, Polish carry no story label)
- Paths are repo-relative from `H:\java\workspaces\workspace-iped\IPED-tika3-upgrade\`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Capture the pre-change baseline and resolve the implementation-time `⚠ VERIFY` unknowns. No production code changes here.

- [ ] T001 Capture the **2.4.0 baseline** on the current branch: run `mvn clean install` and save the log, then process the benchmark case and snapshot item/subitem counts, extracted text, indexed field-key set, error count, wall-clock time, and peak memory into `specs/001-tika3-upgrade/baseline/` (quickstart Step 0; references for SC-001/002/003/005/007)
- [ ] T002 [P] Assemble the curated multi-format **benchmark case** (documents, email, archives, images, SQLite/chat apps, registry, PDF, office; non-sensitive/shareable) under `specs/001-tika3-upgrade/baseline/` (spec Clarification 2026-06-23)
- [ ] T003 [P] Recover the source delta of `tika-core 2.4.0-p1` and `tika-parsers-standard-package 2.4.0-p1` vs. upstream `2.4.0` from the `iped-maven` GitLab repo; record each patched change in `research.md` (D2, risk R4)
- [ ] T004 [P] Confirm the `⚠ VERIFY` items against published **Tika 3.3.1** and record answers in `research.md`: artifact coordinates for `tika-parser-sqlite3-module`/`tika-parser-nlp-module`/`tika-parser-image-module`/`tika-langdetect-optimaize`; `AbstractParser` removal; new homes of `TikaMetadataKeys`/`RESOURCE_NAME_KEY`/`tika.io.IOUtils`; TIKA-4126 fix version; **and the exact PDFBox + POI + commons versions in the Tika 3.3.1 managed dependency set** (to be pinned, not left floating — F1/D1/D3/D4/D5/D6)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The complete compile-clean migration — make every module build against Tika 3.3.1. **No user story can be validated until this phase is complete.** Work proceeds bottom-up along the module dependency order (research D8).

**⚠️ CRITICAL**: Phases 3–5 cannot begin until the project compiles and `mvn clean install` produces the release.

### Dependency & version changes

- [ ] T005 Bump version properties in `pom.xml`: `tika.version` 2.4.0→3.3.1, remove `tika.core.version` (and replace `${tika.core.version}` usages), `pdfbox.version` 2.0.27→**the exact 3.0.x version resolved in T004** (pin it — no floating range) (D1, D6)
- [ ] T006 Reconcile module POMs and add `dependencyManagement` alignment for the Tika-3.3.1 shared libs (PDFBox, POI, commons-io/compress/lang3, metadata-extractor) at the **exact versions from T004**: update `iped-api/pom.xml`, `iped-carvers/iped-carvers-api/pom.xml`, `iped-parsers/iped-parsers-impl/pom.xml` (drop `-p1` + obsolete "2.4.2 not released" exclusions), `iped-engine/pom.xml` (D2, D6, risk R5)

### API surface migration (different files → parallelizable)

- [ ] T007 Create the internal `AbstractParser` compatibility shim at `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/util/AbstractParser.java` ([contracts/parser-spi.md](./contracts/parser-spi.md), D4)
- [ ] T008 Migrate the **76** `extends AbstractParser` classes to import the shim (import-only swap), including `iped-geo/.../parsers/GeofileParser.java` (D4; depends on T007)
- [ ] T009 [P] Migrate relocated metadata/IO symbols across the ~102 affected files (`TikaMetadataKeys`→`TikaCoreProperties`, `RESOURCE_NAME_KEY`, `tika.io.IOUtils`→commons-io), concentrated in `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/util/MetadataUtil.java` (54 refs) (D5)
- [ ] T010 [P] Update `iped-api` public types that re-export Tika classes to compile against 3.3.1: `MediaTypes.java`, `ExtraProperties.java`, `data/IItem.java`, `data/IItemReader.java` (plan Complexity Tracking, D5)
- [ ] T011 [P] Migrate PDFBox 2→3 direct-API usages: `iped-parsers/iped-parsers-impl/src/main/java/iped/parsers/misc/PDFTextParser.java` and any PDFBox-based viewers/carvers (D6, risk R1 — may be a sub-workstream)
- [ ] T012 Make `SyncMetadata` compile against the 3.3.1 `Metadata` API in `iped-engine/src/main/java/iped/engine/tika/SyncMetadata.java` (compile-only here; final revert/keep disposition is US3 / T029) (D3)

### Bottom-up compile gate (sequential, dependency order)

- [ ] T013 Compile `iped-api`, `iped-utils`, `iped-carvers` on 3.3.1: `mvn -pl iped-api,iped-utils,iped-carvers/iped-carvers-api,iped-carvers/iped-carvers-impl -am install` (D8)
- [ ] T014 Compile `iped-parsers/iped-parsers-impl` on 3.3.1: `mvn -pl iped-parsers/iped-parsers-impl -am install` (depends on T007–T011)
- [ ] T015 Compile `iped-viewers` and `iped-geo` on 3.3.1 (`tika.metadata`/PDFBox refs in viewers)
- [ ] T016 Compile `iped-engine` on 3.3.1 (`task/ParsingTask.java`, `task/SignatureTask.java`, `task/index/IndexItem.java`, `io/ParsingReader.java`, `io/ParsingProcess.java`, `webapi/Text.java`)
- [ ] T017 Full `mvn clean install` compiles `iped-app` and produces `target/release/iped-<version>/` (build gate; FR-005)

**Checkpoint**: Project compiles and installs on Tika 3.3.1 — acceptance slices can now begin.

---

## Phase 3: User Story 1 - Evidence processing produces equivalent results (Priority: P1) 🎯 MVP

**Goal**: The upgraded build processes the benchmark with no regression — same format coverage, valid configuration, equivalent text/metadata, old cases still searchable, performance within budget.

**Independent Test**: Process the benchmark on the upgraded build and diff against the T001 baseline: format set ⊇ baseline, ≥99% items equivalent (rest justified), top-level counts exact + subitem counts within ±1%, error count ≤ baseline, a 2.4.0 case opens/searches in the upgraded UI, throughput & peak memory within ≤10%.

- [ ] T018 [US1] Implement the **Lucene field-stability guard** so any Tika 3.x-renamed metadata property still maps to the existing field key, in `iped-parsers/iped-parsers-impl/.../util/MetadataUtil.java` and `iped-engine/.../task/index/IndexItem.java` ([contracts/metadata-field-mapping.md](./contracts/metadata-field-mapping.md), D7, FR-004)
- [ ] T019 [US1] **Config-validity check** — load the upgraded build and verify IPED's Tika-referencing configuration resolves under the 3.x model: `iped-app/resources/config/conf/CustomSignatures.xml`, MIME-type configs, `CategoriesConfig.json`, and any parser/signature config referencing a Tika type/class. Confirm no unresolved/relocated Tika class-name references remain (FR-008; spec Edge Case "Custom configuration validity")
- [ ] T020 [US1] Process the benchmark case on the upgraded build and produce the **upgraded snapshot** (counts, text, metadata, field-key set, errors, timing, peak memory) into `specs/001-tika3-upgrade/upgraded/` (quickstart Steps 4–6; depends on T018, T019)
- [ ] T021 [P] [US1] **Format-coverage diff** vs. baseline — assert zero supported-format regressions; remap/re-add any module that dropped a format (SC-001, FR-002, risk R6)
- [ ] T022 [P] [US1] **Extraction-parity & count diff** vs. baseline — confirm top-level item counts match exactly, subitem counts within ±1%, and ≥99% items have equivalent text+metadata; triage and justify the <1% (SC-002, FR-003)
- [ ] T023 [P] [US1] **Backward-compatibility check** — open the baseline (2.4.0) case in the upgraded `BootstrapUI`; assert indexed field-key set is identical and queries still match; confirm error count ≤ baseline (SC-005, SC-003, FR-004)
- [ ] T024 [P] [US1] **Performance comparison** — throughput regression ≤10% and peak-memory increase ≤10% vs. baseline (SC-007)

**Checkpoint**: User Story 1 independently validated — this is the MVP (the upgrade is forensically safe).

---

## Phase 4: User Story 2 - IPED builds, packages, and tests green on Tika 3.3.1 (Priority: P2)

**Goal**: Full multi-module build + existing test suite pass at ≥ baseline rate + release package produced, with a clean dependency tree.

**Independent Test**: `mvn -B package` succeeds and the test pass rate is ≥ the T001 baseline, with every remaining failure explained and pre-existing.

- [ ] T025 [US2] Migrate **test sources** broken only by Tika 3.x API/package changes to the new API while preserving their original assertions — covers `iped-parsers/.../test/.../AbstractPkgTest.java` helpers and parser tests using metadata-key symbols (FR-006, [contracts/parser-spi.md](./contracts/parser-spi.md))
- [ ] T026 [US2] Run `mvn -pl iped-parsers/iped-parsers-impl test` and `mvn -pl iped-engine test`; resolve runtime test failures until pass rate ≥ baseline (SC-004; depends on T025)
- [ ] T027 [P] [US2] Run `mvn -B package --file pom.xml` (CI-equivalent) and confirm the release artifact is produced without Tika-related errors (FR-005, quickstart Step 2)
- [ ] T028 [P] [US2] **Dependency-tree audit** — `mvn dependency:tree` shows a single Tika 3.3.1 set + aligned PDFBox/POI/commons (exact versions from T004) with no conflicts affecting Lucene/webapi/other consumers (FR-009, risk R5, quickstart Step 3)

**Checkpoint**: User Story 2 independently validated — the upgrade is buildable and shippable.

---

## Phase 5: User Story 3 - IPED-specific Tika patches and workarounds are reconciled (Priority: P3)

**Goal**: Every `-p1` fork and Tika workaround has an intentional, recorded disposition.

**Independent Test**: Walk the disposition ledger — each Patched Fork and Workaround from [data-model.md](./data-model.md) is either kept-with-justification or removed-because-fixed; none undecided.

- [ ] T029 [US3] Finalize **SyncMetadata / TIKA-4126** disposition: if fixed in 3.3.1, revert commit `b673cf4` and route callers back to plain `Metadata`; else keep with recorded reason. Update the explanatory comment at `pom.xml:33` either way (D3, FR-007, SC-006)
- [ ] T030 [P] [US3] Finalize **`tika-core` `-p1`** disposition (default drop-to-vanilla; re-fork a `3.3.1-p1` only if a still-needed patch is absent upstream, per T003 findings) (D2, FR-010)
- [ ] T031 [P] [US3] Finalize **`tika-parsers-standard-package` `-p1`** disposition: replace the obsolete "2.4.2 not released" fork with vanilla 3.3.1 + Maven `<exclusions>` for `metadata-extractor` / stock `tika-parser-image-module` only as still required (D2, FR-007)
- [ ] T032 [US3] Record the **disposition ledger** (every Patched Fork + Workaround → outcome + rationale) in `research.md` (SC-006; depends on T029, T030, T031)

**Checkpoint**: User Story 3 independently validated — no obsolete forks or undecided workarounds remain.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and final end-to-end validation across all stories.

- [ ] T033 [P] Update dependency tables in `CLAUDE.md` (root §3: Tika 2.4.0→3.3.1, PDFBox), `iped-engine/CLAUDE.md` (§1, §14), and `iped-parsers/CLAUDE.md` to reflect the new versions
- [ ] T034 [P] Update `ThirdParty.txt` and `licenses/` for changed dependency versions (Tika, PDFBox, POI, commons)
- [ ] T035 [P] Add a `ReleaseNotes.txt` entry for the Tika 3.3.1 upgrade
- [ ] T036 Run the full [quickstart.md](./quickstart.md) validation end-to-end and attach results + the disposition ledger to `specs/001-tika3-upgrade/` (confirms SC-001 … SC-007)

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: No dependencies — start immediately. T001 captures the baseline that every story diffs against.
- **Foundational (Phase 2)**: Depends on Setup (needs T004 verifications/version pins and T003 patch delta). **Blocks all user stories.**
- **User Stories (Phase 3–5)**: All depend on Foundational completion (T017 build gate). They are mutually **independent** acceptance slices and may proceed in parallel or in priority order P1 → P2 → P3.
- **Polish (Phase 6)**: After the desired stories are complete.

### User story dependencies

- **US1 (P1)**: Needs only Foundational (a runnable build). Independent of US2/US3.
- **US2 (P2)**: Needs only Foundational. Independent of US1/US3.
- **US3 (P3)**: Needs Foundational + the T003 patch-delta findings. Independent of US1/US2.

### Within Foundational (critical ordering)

- T005 → T006 (versions before POM reconciliation)
- T007 → T008 (shim before import swaps)
- T009, T010, T011, T012 are different files → parallel after T006
- Compile gates are strictly sequential bottom-up: T013 → T014 → T015 → T016 → T017

### Within User Story 1 (ordering)

- T018 (guard) and T019 (config validity) → T020 (process) → T021, T022, T023, T024 (independent diffs/checks)

---

## Parallel Opportunities

- **Setup**: T002, T003, T004 in parallel (T001 runs on current code first).
- **Foundational**: after T006/T007 — T008, T009, T010, T011, T012 touch different files and can run in parallel before the sequential compile gates.
- **US1**: after T020 — T021, T022, T023, T024 are independent diffs/checks, all [P].
- **US2**: T027 and T028 parallel with the T025→T026 test-fix chain.
- **US3**: T030 and T031 parallel; T032 after them.
- **Polish**: T033, T034, T035 parallel; T036 last.

### Parallel example — User Story 1 analyses

```bash
# After T020 produces the upgraded snapshot, run the four checks together:
Task: "Format-coverage diff vs baseline (T021)"
Task: "Extraction-parity & count diff vs baseline (T022)"
Task: "Backward-compatibility check on old case (T023)"
Task: "Performance comparison vs baseline (T024)"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1: Setup — capture baseline + benchmark + verifications/version pins.
2. Phase 2: Foundational — full compile-clean migration to 3.3.1 (the bulk of the work).
3. Phase 3: User Story 1 — validate forensic parity.
4. **STOP and VALIDATE**: zero format regressions, valid config, ≥99% parity, old cases work, ≤10% perf. This proves the upgrade is forensically safe — the MVP.

### Incremental Delivery

1. Setup + Foundational → project builds on 3.3.1.
2. US1 → forensic parity proven (MVP).
3. US2 → tests green + release packaged + clean dependency tree.
4. US3 → patch/workaround ledger finalized.
5. Polish → docs, third-party notes, release notes, full quickstart run.

---

## Requirement Coverage

| Requirement | Task(s) |
|---|---|
| FR-001 use Tika 3.3.1 | T005, T006 |
| FR-002 no format-coverage loss | T021 |
| FR-003 custom parsers equivalent | T008, T022, T025 |
| FR-004 field-key/metadata stability | T018, T023 |
| FR-005 build/package green | T017, T027 |
| FR-006 tests pass ≥ baseline | T025, T026 |
| FR-007 workarounds reconciled | T029, T031, T032 |
| FR-008 Tika config validity | **T019** |
| FR-009 align-up shared deps | T006, T011, T028 |
| FR-010 record fork decision | T030, T032 |
| SC-001 format set ⊇ baseline | T021 |
| SC-002 ≥99% parity + count tolerance | T022 |
| SC-003 errors ≤ baseline | T023 |
| SC-004 test pass ≥ baseline | T026 |
| SC-005 release runs; old case opens | T023, T027 |
| SC-006 every workaround recorded | T032 |
| SC-007 ≤10% throughput/memory | T001, T024 |

---

## Notes

- **[P]** = different files, no dependency on incomplete tasks.
- The largest mechanical cohorts are T008 (76 files, import-only via shim) and T009 (~102 files, symbol relocation); T011 (PDFBox 2→3) is the largest behavioral risk (R1) and may warrant being split further during execution.
- Commit after each task or logical cohort; keep the 2.4.0 baseline artifacts under `specs/001-tika3-upgrade/baseline/` for the duration.
- Do **not** rename any Lucene field key or change `AppAnalyzer` behavior (CLAUDE.md guardrails); the T018 guard exists to enforce this.
- Verify migrated tests preserve their **original** assertions — do not weaken a test to make it pass (FR-006).
