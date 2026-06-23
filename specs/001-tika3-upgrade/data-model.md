# Phase 1 Data Model: Tika 3.3.1 Upgrade

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Date**: 2026-06-23

This is a dependency/API migration, so the "entities" are the **migration units** being tracked to done — not runtime domain objects. Each entity below has attributes, a disposition lifecycle (state), and validation rules drawn from the spec's functional requirements and success criteria.

---

## Entity: Tika Artifact

A Maven artifact in the `org.apache.tika` group consumed by IPED.

| Attribute | Description |
|---|---|
| `artifactId` | e.g., `tika-core`, `tika-parsers-standard-package`, `tika-parser-sqlite3-module`, `tika-parser-nlp-module`, `tika-langdetect-optimaize` |
| `currentVersion` | `2.4.0` or `2.4.0-p1` |
| `targetVersion` | `3.3.1` |
| `consumers` | IPED modules declaring it (`iped-api`, `iped-carvers-api`, `iped-parsers-impl`, `iped-engine`) |
| `existsAt331` | whether the same coordinates exist at 3.3.1 (**⚠ VERIFY**) |

**Lifecycle**: `pinned@2.4.x` → `version-bumped@3.3.1` → `compiles` → `tests-green`.

**Validation**: FR-001 (all on 3.3.1); FR-002/SC-001 (no artifact removal silently drops a format → if an artifact is gone at 3.3.1, its formats must be re-provided via the replacement module).

**Instances** (5): see [research.md §1](./research.md).

---

## Entity: Patched Fork (`-p1`)

An IPED-maintained patched build of an upstream Tika artifact.

| Attribute | Description |
|---|---|
| `artifactId` | `tika-core`, `tika-parsers-standard-package` |
| `patchReasonKnown` | documented rationale (e.g., *"workaround while tika 2.4.2 is not released"* for the parsers package) |
| `patchDelta` | actual source diff vs. upstream `2.4.0` (**⚠ VERIFY** — recover from `iped-maven` repo) |
| `disposition` | one of `drop-vanilla`, `reproduce-via-exclusions`, `re-fork-3.3.1` |

**Lifecycle**: `patched@2.4.0-p1` → `delta-recovered` → `delta-assessed-vs-3.3.1` → (`drop-vanilla` | `reproduce-via-exclusions` | `re-fork-3.3.1`) → `recorded`.

**Validation**: FR-007, FR-010, SC-006 — every fork ends in a recorded disposition; default is `drop-vanilla` (research D2).

**Instances** (2): `tika-core` 2.4.0-p1; `tika-parsers-standard-package` 2.4.0-p1 (with exclusions of `metadata-extractor`, stock `tika-parser-image-module`, `xml-apis`, `commons-logging`).

---

## Entity: Migration Unit (code)

A cohort of source files sharing one breaking-change cause, migrated together.

| Attribute | Description |
|---|---|
| `name` | e.g., `AbstractParser-subclasses`, `metadata-IO-symbols`, `pdfbox-direct-api`, `iped-api-tika-types` |
| `cause` | the upstream removal/relocation driving the change |
| `fileCount` | measured size |
| `strategy` | how it is migrated (per research decision) |
| `behaviorPreserving` | must be `true` (parity) |

**Lifecycle**: `identified` → `strategy-chosen` → `migrated` → `compiles` → `unit-tests-green` → `parity-verified`.

**Validation**: FR-003 (custom parsers keep working), FR-006 (tests migrated preserve intent), SC-002 (parity).

**Instances** (🔁 rebalanced post-T004 — see [research.md §5](./research.md)):

| Unit | Cause | Size | Strategy | Status |
|---|---|---|---|---|
| **`pdfbox-direct-api`** | PDFBox 2.0.27→3.0.7 (breaking) | **6 files**: `iped-parsers .../util/PDFToImage.java`, `.../util/PDFToThumb.java`, `iped-viewers .../PDFBoxViewer.java`, `iped-app .../timelinegraph/cache/persistance/CachePersistance.java`, `.../cache/TimeIndexedMap.java`, `.../datasets/IpedTimelineDataset.java` | `Loader.loadPDF`, `PDFRenderer`, + new `pdfbox-io` (`org.apache.pdfbox.io.*`) for the timeline cache (D6) | **CRITICAL PATH** |
| `poi-direct-api` | POI→5.5.1 | 9 files (parsers `GenericOLEParser`/`OFCParser`/`OFXParser`/`RFC822Parser`/`MFCParser`/`discord cache Index`, viewers `EmailViewer`/`MsgViewer`, engine `Util`) | compile-check + align (D6) | secondary |
| `pom-dependency-graph` | version + transitive alignment | 5 POMs | align-up + `dependencyManagement` at the T004 versions (D6) | required |
| `sync-metadata` | TIKA-4126 / `Metadata` signature | 1 file | compile-check, then revert-or-keep (D3) | 1 file |
| `iped-api-tika-types` | re-exports `Metadata`/`MediaType`/`Property` | 4 public types | **verified compile on 3.3.1** → likely no edit | low |
| ~~`AbstractParser-subclasses`~~ | ~~removed~~ → **present (deprecated)** | 76 files | **compile unchanged** — optional deprecation cleanup | **NULLIFIED** |
| ~~`metadata-IO-symbols`~~ | symbols **still present / unused** | — | `TikaCoreProperties.RESOURCE_NAME_KEY` present; `TikaMetadataKeys`/`io.IOUtils` unused | **NULLIFIED** |

---

## Entity: Shared Dependency (transitive, align-up)

A non-Tika library Tika builds on that IPED also pins/uses directly.

| Attribute | Description |
|---|---|
| `artifactId` | `pdfbox`, `poi`, `commons-io`, `commons-compress`, `commons-lang3`, `metadata-extractor`, … |
| `ipedPinned` | current IPED-pinned version (e.g., PDFBox 2.0.27) |
| `tika331Expected` | version in the Tika 3.3.1 dependency set (**⚠ VERIFY** via BOM) |
| `directIpedUse` | whether IPED code calls its API directly (drives migration cost) |
| `action` | `align-up` (default per clarification) |

**Lifecycle**: `pinned-old` → `target-identified` → `bumped` → `iped-callers-migrated` → `tree-conflict-free` (`mvn dependency:tree`).

**Validation**: FR-009, clarified "align up" decision, R1/R5 mitigation.

**Instances** (verified T004 — exact targets, no longer floating):

| Library | IPED 2.4.0 | Tika 3.3.1 target | Direct IPED use? |
|---|---|---|---|
| PDFBox (+ fontbox/xmpbox/pdfbox-tools) | 2.0.27 | **3.0.7** | yes (6 files) |
| `pdfbox-io` | — (n/a in 2.x) | **3.0.7** (new module) | yes (timeline cache) |
| jbig2-imageio | 3.0.4 | 3.0.5 | no |
| Apache POI | (transitive) | **5.5.1** | yes (9 files) |
| commons-io | (transitive) | 2.22.0 | indirect |
| commons-compress | 1.27.1 | 1.28.0 | yes |
| commons-lang3 | (transitive) | 3.20.0 | indirect |
| metadata-extractor | excluded in `-p1` | 2.20.0 | via image module |

---

## Entity: Workaround / Guard

An IPED-side accommodation for Tika behavior that must be re-evaluated.

| Attribute | Description |
|---|---|
| `name` | `SyncMetadata`, `image-module-exclusion`, `metadata-extractor-exclusion`, `lucene-field-mapping-guard` |
| `stillNeededAt331` | boolean (**⚠ VERIFY**) |
| `disposition` | `remove` / `keep` / `add` (guard) with recorded rationale |

**Validation**: SC-006 (no undecided workaround); D7 adds the Lucene-field-mapping guard to satisfy FR-004/SC-005.

---

## Cross-cutting invariants (must hold post-migration)

1. **Format coverage** — set of detected/parsed formats is unchanged or a superset (SC-001).
2. **Field stability** — no Lucene field key or analyzer behavior changes (FR-004, SC-005, D7).
3. **Parity** — ≥99% of benchmark items keep equivalent text+metadata; rest triaged (SC-002).
4. **Performance** — ≤10% throughput regression and ≤10% peak-memory increase (SC-007).
5. **Disposition completeness** — every Patched Fork and Workaround has a recorded outcome (SC-006).
