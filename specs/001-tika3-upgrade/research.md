# Phase 0 Research: Upgrade Apache Tika to 3.3.1

**Feature**: [spec.md](./spec.md) · **Date**: 2026-06-23

This document resolves the open technical unknowns for migrating IPED from Apache Tika **2.4.0 / 2.4.0-p1** to **3.3.1**, grounded in the current codebase. Items marked **⚠ VERIFY** must be confirmed against the actual published 3.3.1 artifacts/Javadoc during implementation; the migration plan assumes the worst-case effort for each so a wrong assumption does not invalidate the schedule.

---

## 1. Current Tika footprint (measured)

| Fact | Value | Source |
|---|---|---|
| Tika version property | `tika.version = 2.4.0` | `pom.xml:33` |
| Patched core version | `tika.core.version = 2.4.0-p1` | `pom.xml:34` |
| Files importing `org.apache.tika.*` | **287** | repo grep |
| Files with `extends AbstractParser` | **76** (82 occurrences) | repo grep |
| Files using metadata-key / Tika-IO symbols (`TikaCoreProperties`, `TikaMetadataKeys`, `RESOURCE_NAME_KEY`, `org.apache.tika.io.IOUtils`) | **102** (~515 occurrences); `MetadataUtil.java` = 54 | repo grep |

**Tika artifacts consumed today:**

| Artifact | Version | Declared in |
|---|---|---|
| `tika-core` | `2.4.0-p1` (patched) | `iped-api`, `iped-carvers-api`, `iped-parsers-impl` |
| `tika-parsers-standard-package` | `2.4.0-p1` (patched; excludes `metadata-extractor`, stock `tika-parser-image-module`, `xml-apis`, `commons-logging`) | `iped-parsers-impl` |
| `tika-parser-sqlite3-module` | `2.4.0` | `iped-parsers-impl` |
| `tika-parser-nlp-module` | `2.4.0` | `iped-engine` |
| `tika-langdetect-optimaize` | `2.4.0` | `iped-engine` |

**Key insight:** IPED maintains **two** `-p1` patched artifacts (`tika-core` and `tika-parsers-standard-package`), not one. The `tika-parsers-standard-package` patch is explicitly annotated *"workaround while tika 2.4.2 is not released"* (`iped-parsers-impl/pom.xml:34,49`) — it excludes the stock image module and `metadata-extractor`. That rationale is **obsolete at 3.3.1**.

---

## 2. Decisions

### D1 — Target artifact set & versions

- **Decision**: Move every Tika artifact to a single `tika.version = 3.3.1` property and collapse the separate `tika.core.version`. Confirm each artifact still exists under the same coordinates at 3.3.1: `tika-core`, `tika-parsers-standard-package`, `tika-parser-sqlite3-module`, `tika-parser-nlp-module`, `tika-langdetect-optimaize`. **⚠ VERIFY** the sqlite3/nlp/image module artifactIds are unchanged in 3.x.
- **Rationale**: A single version property removes the split-version drift the `-p1` scheme created and matches the spec's parity goal (FR-001).
- **Alternatives considered**: Keep distinct core vs. parsers versions — rejected; the split only existed to carry the `-p1` patches, which D2 retires.

### D2 — Disposition of the `-p1` patched forks (resolves FR-010, Story 3)

- **Decision**: Default to **vanilla upstream 3.3.1** for both `tika-core` and `tika-parsers-standard-package`. Before deleting the `-p1` references, recover the patch delta of each `2.4.0-p1` artifact (from the `iped-maven` GitLab repo / IPED Tika fork) and check each change against 3.3.1. Re-introduce a patch **only** if a still-needed change is absent upstream; record the outcome.
- **Rationale**: The documented reason for the `tika-parsers-standard-package` patch ("while tika 2.4.2 is not released") is long superseded by 3.3.1. The stock-image-module and `metadata-extractor` exclusions are dependency-shaping, reproducible with normal Maven `<exclusions>` without a fork. Carrying obsolete forks is the maintainability risk Story 3 targets.
- **Alternatives considered**: Build a fresh `3.3.1-p1` fork up front — rejected as default; only justified if a concrete still-unfixed upstream defect is found.
- **⚠ VERIFY**: Obtain and diff the `2.4.0-p1` sources vs. upstream `2.4.0` to enumerate exactly what was patched.

### D3 — `SyncMetadata` / TIKA-4126 workaround

- **Decision**: Treat the `SyncMetadata` wrapper (`iped-engine/.../tika/SyncMetadata.java`, commit `b673cf4`) as **revert-candidate**. Confirm TIKA-4126 (Metadata thread-safety) is resolved at 3.3.1; if so, remove the synchronized wrapper and route callers back to plain `Metadata`. If not resolved, keep it but migrate it to any changed `Metadata` method signatures.
- **Rationale**: The `pom.xml:33` comment explicitly ties this workaround to the upgrade ("can be reverted when version is upgraded to include solution of TIKA-4126").
- **⚠ VERIFY**: TIKA-4126 fix version, and that all overridden `Metadata` methods still exist with the same signatures in 3.3.1.
- **Alternatives considered**: Keep `SyncMetadata` indefinitely — rejected; defeats the stated cleanup intent and adds lock contention.

### D4 — `AbstractParser` removal/relocation (largest mechanical change)

- **Decision**: Plan for migrating all **76** `extends AbstractParser` classes. In Tika 3.x the deprecated `org.apache.tika.parser.AbstractParser` is removed; parsers implement `org.apache.tika.parser.Parser` directly (providing `getSupportedTypes` + `parse`) and drop the empty no-arg `parse(...)` overload that `AbstractParser` supplied. Introduce a thin internal base class (e.g., `iped.parsers.util.AbstractParser` shim) so the 76 subclasses change only their `import`, minimizing churn and keeping behavior identical. **⚠ VERIFY** the exact removed/renamed member set at 3.3.1.
- **Rationale**: A one-line import swap across 76 files via a local shim is lower-risk and more reviewable than editing each class body, and preserves the existing `serialVersionUID`/behavior.
- **Alternatives considered**: Edit each subclass to implement `Parser` directly — rejected; 76× larger diff, higher regression surface.
- **🔁 UPDATE (T004, verified 2026-06-23)**: `org.apache.tika.parser.AbstractParser` is **STILL PRESENT in tika-core 3.3.1** (marked `@Deprecated`, methods intact). The 76 subclasses **compile unchanged**. The shim/migration is therefore **OPTIONAL deprecation cleanup, not required** for the upgrade — T007/T008 are downgraded to optional. See §5.

### D5 — Metadata constants & Tika IO relocations

- **Decision**: Mechanical migration of the ~515 references. Expected mappings to confirm: `org.apache.tika.metadata.TikaMetadataKeys` → `TikaCoreProperties`; `Metadata.RESOURCE_NAME_KEY` → `TikaCoreProperties.RESOURCE_NAME_KEY`; `org.apache.tika.io.IOUtils` → `org.apache.commons.io.IOUtils` (Tika's bundled copy removed). Concentrate effort in `MetadataUtil.java` (54 refs). **⚠ VERIFY** each constant's new home in 3.3.1.
- **Rationale**: These are find-and-replace class relocations, not behavioral changes; risk is missing one, which the compiler will catch.
- **Alternatives considered**: None — these are forced by upstream removal of deprecated symbols.
- **🔁 UPDATE (T004, verified 2026-06-23)**: For IPED specifically this is **near-empty**. IPED references `RESOURCE_NAME_KEY` exclusively as **`TikaCoreProperties.RESOURCE_NAME_KEY` (128×)**, which **still exists** in 3.3.1. `TikaMetadataKeys` and `org.apache.tika.io.IOUtils` have **0 IPED usages** (and `io.IOUtils` is still present anyway). So **no metadata/IO symbol migration is needed** — T009 is essentially a no-op. See §5.

### D6 — Transitive/shared dependency alignment (implements clarified "align up")

- **Decision**: Bump IPED's directly-pinned shared libraries to the versions Tika 3.3.1 is built against, and migrate IPED code that calls their changed APIs. Highest-impact: **PDFBox 2.0.27 → 3.0.x** (Tika 3.x's PDF module targets PDFBox 3.0; PDFBox 2→3 is itself a breaking API change used directly by `PDFTextParser`, viewers, carvers). Also review **Apache POI**, **commons-io/commons-compress/commons-lang3**, and **metadata-extractor** alignment. **⚠ VERIFY**: the value `3.0.x` is a placeholder — the **exact** PDFBox version (and the POI/commons versions) MUST be read from the Tika 3.3.1 managed dependency set in task T004, pinned in `pom.xml` in T005, and recorded here, before parity/perf gates run. Do not leave `3.x` as a final value (a floating range would make SC-002/SC-007 non-deterministic).
- **Rationale**: Clarification session chose "align up" to avoid `NoSuchMethodError`/`LinkageError` from running Tika 3.x against older transitive libs (spec Clarifications 2026-06-23).
- **Alternatives considered**: Hold IPED's pins and force-downgrade Tika's deps — rejected by clarification (runtime-incompatibility risk).
- **Note**: PDFBox 3 migration may be large enough to track as its own work-stream inside this feature.
- **🔁 UPDATE (T004, verified 2026-06-23)**: Concrete versions resolved from the Tika 3.3.1 tree — **PDFBox 3.0.7** (pdfbox/fontbox/pdfbox-io/pdfbox-tools/xmpbox), jbig2-imageio 3.0.5; **POI 5.5.1**; commons-io **2.22.0**, commons-compress **1.28.0**, commons-lang3 **3.20.0**, commons-codec 1.22.0, commons-collections4 4.5.0; metadata-extractor **2.20.0**. The stock **`tika-parser-image-module:3.3.1` is a normal transitive** of the standard package now → the IPED "exclude stock image module" workaround is **obsolete** (validates D2/Story 3). With D4/D5 nullified, **PDFBox 2→3 is the dominant real migration cost** of this upgrade. See §5.

### D7 — Lucene field / case backward-compatibility guard

- **Decision**: Do **not** change any Lucene field key or analyzer behavior. Where Tika 3.x renames a metadata property that IPED currently maps to a stable index field, add an explicit mapping in `MetadataUtil`/`IndexItem` to preserve the existing field name (FR-004, SC-005). Validate by opening a pre-upgrade case in the upgraded build.
- **Rationale**: CLAUDE.md flags `BasicProps`/`IndexItem` field strings and `AppAnalyzer` config as case-invalidating if changed. Backward compatibility is SC-005.
- **Alternatives considered**: Let field names follow Tika's new property names — rejected; breaks existing cases.

### D8 — Build, test & benchmark approach

- **Decision**: Stage the build module-by-module along the dependency order (`iped-api` → `iped-utils` → `iped-carvers` → `iped-parsers` → `iped-viewers`/`iped-geo` → `iped-engine` → `iped-app`), getting each to compile before the next. Use the existing JUnit suites as the per-format gate (FR-006) and assemble the curated multi-format benchmark case (per clarification) for the parity (SC-002) and performance (SC-007) comparison, captured as a baseline on 2.4.0 first.
- **Rationale**: Tika's foundational artifacts sit at the bottom of the module graph; compiling bottom-up localizes breakage. Capturing the 2.4.0 baseline before changing code is required to measure SC-002/SC-007.
- **Alternatives considered**: Big-bang version bump then fix all compile errors at once — workable but produces an unreviewable diff and obscures which module caused a regression.

---

## 3. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | PDFBox 2→3 migration larger than expected (direct API use in parsers/viewers/carvers) | High | High | Treat as a sub-workstream (D6); baseline PDF extraction before/after on benchmark |
| R2 | `AbstractParser` removal touches 76 files | High | Medium | Local shim base class (D4) limits churn to imports |
| R3 | Hidden behavioral changes in stock Tika parsers alter extracted text/metadata | Medium | High | Benchmark parity gate ≥99% (SC-002); triage every diff |
| R4 | `-p1` patch delta unknown / unavailable | Medium | Medium | Recover sources from `iped-maven` repo before removing (D2); fall back to reproducing via Maven exclusions |
| R5 | Transitive POI/commons conflict with non-Tika consumers (Lucene, webapi, etc.) | Medium | Medium | `mvn dependency:tree` audit; pin via `dependencyManagement` |
| R6 | A stock parser/module renamed or removed in 3.x drops a supported format | Low | High | Format-coverage check (SC-001) on benchmark; remap or re-add module |
| R7 | Metadata property rename silently changes a Lucene field | Low | High | Explicit field mapping guard (D7); open old case post-upgrade |

---

## 4. Resolved unknowns summary

All Technical Context unknowns are resolved to a **Decision** above. Residual **⚠ VERIFY** items are confirmations against the published 3.3.1 API performed during implementation; none block planning, and each has a worst-case effort already budgeted in the plan. No `NEEDS CLARIFICATION` remains.

---

## 5. T004 verification results (executed 2026-06-23)

Resolved against Maven Central using a throwaway probe POM (`dependency:tree`) and `javap`/`unzip -l` on the downloaded `tika-core-3.3.1.jar`. **These results materially shrink the API-migration scope and refocus the work on PDFBox.**

### 5.1 Artifact coordinates — all exist at 3.3.1 ✅
`tika-core`, `tika-parsers-standard-package`, `tika-parser-sqlite3-module`, `tika-parser-nlp-module`, `tika-langdetect-optimaize` all resolve at `3.3.1` (BUILD SUCCESS). The standard package transitively includes `tika-parser-image-module:3.3.1` and ~25 other parser modules.

### 5.2 Transitive shared-dependency versions (the align-up targets) ✅
| Library | IPED 2.4.0 pin | Tika 3.3.1 brings |
|---|---|---|
| PDFBox (pdfbox/fontbox/pdfbox-io/tools/xmpbox) | 2.0.27 | **3.0.7** |
| jbig2-imageio | 3.0.4 | 3.0.5 |
| Apache POI (poi/poi-ooxml/scratchpad/ooxml-full) | — | **5.5.1** |
| commons-io | (transitive) | **2.22.0** |
| commons-compress | 1.27.1 | **1.28.0** |
| commons-lang3 | — | **3.20.0** |
| metadata-extractor (drewnoakes) | (excluded in `-p1`) | **2.20.0** |

### 5.3 API reality — three plan assumptions corrected 🔁
| Symbol | Plan assumed | Verified in tika-core 3.3.1 | IPED usage | Verdict |
|---|---|---|---|---|
| `org.apache.tika.parser.AbstractParser` | removed (76-file migration) | **present, `@Deprecated`**, methods intact | 76 subclasses | **Compiles unchanged.** Shim optional cleanup only — **D4 nullified** |
| `org.apache.tika.metadata.TikaMetadataKeys` | relocated | absent | **0 files** | No impact |
| `org.apache.tika.io.IOUtils` | moved to commons-io | **still present** | **0 files** | No impact |
| `TikaCoreProperties.RESOURCE_NAME_KEY` | (relocation risk) | **present** | **128 refs** | **No change needed** — IPED already on the modern form. **D5 nullified** |

### 5.4 Net effect on scope
- **D4 (76 files)** and **D5 (~102 files)** are **no longer migration work** for compile-correctness — at most optional deprecation cleanup (`AbstractParser`).
- The **dominant real work is PDFBox 2.0.27 → 3.0.7** (breaking API used directly by `PDFTextParser`, PDFBox-based viewers, and any PDF carver) and POI → 5.5.1 alignment, plus behavioral-parity validation on the benchmark.
- The `-p1` `tika-parsers-standard-package` image-module exclusion is **confirmed obsolete** (Story 3 / D2).
- **Still to confirm (not yet done in T004)**: the TIKA-4126 fix version (needed for the `SyncMetadata` disposition, T029) and whether any `Metadata` method overridden by `SyncMetadata` changed signature (T012).

### 5.5 Recommended plan adjustment
Re-balance tasks: demote T007/T008 (shim/76-file) to optional, treat T009 as a no-op, and elevate **T011 (PDFBox 2→3)** to the critical-path workstream. This is a candidate for a quick `/speckit-plan` refresh before heavy implementation.
