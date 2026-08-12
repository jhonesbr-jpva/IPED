# Feature Specification: Upgrade Apache Tika to 3.3.1

**Feature Branch**: `tika3-upgrade`

**Created**: 2026-06-23

**Status**: Draft

**Input**: User description: "Vamos migrar o Tika do IPED para versão 3.3.1"

## Overview

IPED relies on Apache Tika for content type detection and text/metadata extraction across roughly 70 categories of custom parsers and many core formats. The project currently consumes Tika **2.4.0** (with a locally patched `tika-core` published as `2.4.0-p1`). This feature upgrades that dependency to Apache Tika **3.3.1** — a major-version jump (2.x → 3.x) that carries breaking API and packaging changes.

The intent is a **maintenance/parity upgrade**: IPED must keep doing everything it does today (same supported formats, same extraction quality, same case compatibility) while running on the newer, supported, security-patched Tika line. Adoption of net-new Tika 3.x capabilities is explicitly out of scope for this effort.

## Clarifications

### Session 2026-06-23

- Q: What measurable equivalence threshold defines "no extraction regression" on the benchmark? → A: ≥99% of items must have equivalent extracted text & metadata; the remaining <1% are manually reviewed and justified.
- Q: What performance regression is acceptable after the upgrade? → A: ≤10% throughput regression and ≤10% peak-memory increase on the benchmark case vs. baseline.
- Q: How are transitive/shared dependencies (PDFBox, POI, etc.) reconciled with Tika 3.x? → A: Align up — bump IPED's pinned shared libraries to the versions Tika 3.3.1 expects, and migrate IPED code that uses the older APIs.
- Q: What constitutes the benchmark case used as the regression gate? → A: A curated multi-format benchmark case covering high-traffic categories (documents, email, archives, images, SQLite/chat, registry, PDF, office), used alongside the existing per-parser test corpus.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Evidence processing produces equivalent results (Priority: P1)

A forensic analyst processes a case (forensic image or logical source) with IPED after the upgrade and obtains the same evidentiary outcome they would have obtained before it: the same files are recognized, the same item/subitem tree is produced, and text and metadata are extracted with equivalent fidelity. No supported format silently stops being parsed.

**Why this priority**: This is the whole point of the upgrade — it must not degrade IPED's core forensic function. A regression here directly undermines investigations and is the highest risk of a major Tika version jump.

**Independent Test**: Process a representative benchmark case (covering documents, email, archives, images, SQLite/chat apps, registry, and other high-traffic formats) on the current build and on the upgraded build, then compare item counts, extracted text, and key metadata. The story passes if results are equivalent within a documented tolerance and no supported format loses coverage.

**Acceptance Scenarios**:

1. **Given** a benchmark case that processes cleanly on the current Tika 2.4.0 build, **When** the same case is processed on the Tika 3.3.1 build, **Then** the set of supported/recognized formats is the same or a superset, and no previously parsed format becomes unparsed.
2. **Given** a file whose text and metadata IPED extracts today, **When** it is processed on the upgraded build, **Then** the extracted text and indexed metadata are equivalent (within a documented, justified tolerance) and downstream tasks (hashing of text, regex, NER, thumbnails) still run.
3. **Given** a case processed before the upgrade, **When** it is opened in the upgraded analysis UI, **Then** it remains fully searchable and viewable (existing indexes stay readable).

---

### User Story 2 - IPED builds, packages, and tests green on Tika 3.3.1 (Priority: P2)

An IPED maintainer can build the full multi-module project against Tika 3.3.1, produce the packaged release, and run the existing automated test suite to completion with results at least as good as before the upgrade.

**Why this priority**: A clean, reproducible build and green tests are the gate that makes the parity claim of Story 1 verifiable and the change shippable. Without it the upgrade cannot be merged or released.

**Independent Test**: Run the standard build (all modules) and the existing test suite against the upgraded dependency. The story passes if compilation succeeds, the release package is produced, and the test pass rate is equal to or better than the pre-upgrade baseline (with any remaining failures explained and pre-existing).

**Acceptance Scenarios**:

1. **Given** the upgraded dependency version, **When** the full project is built, **Then** all modules compile and the final release artifact is produced without Tika-related build errors.
2. **Given** an automated test that fails only because of a Tika 3.x API or package change, **When** it is migrated to the new API, **Then** its original assertions and intent are preserved and the test passes.
3. **Given** the upgraded build, **When** transitive dependencies pulled in by Tika 3.x (e.g., document/office/PDF libraries) are resolved, **Then** they do not conflict with or break other IPED features.

---

### User Story 3 - IPED-specific Tika patches and workarounds are reconciled (Priority: P3)

An IPED maintainer reviews each IPED-local patch and workaround layered on top of Tika today and reconciles it with upstream 3.3.1: workarounds for bugs fixed upstream are removed, and patches still required are carried forward in a maintainable way.

**Why this priority**: Carrying obsolete patches or silently dropping still-needed ones is a long-term maintainability and correctness risk. It matters, but it is bounded cleanup that depends on the outcomes of Stories 1 and 2, so it ranks last.

**Independent Test**: Enumerate the existing Tika-related patches/workarounds (including the patched `tika-core` fork and the `SyncMetadata` workaround tied to TIKA-4126), check each against the 3.3.1 changelog/behavior, and confirm the resulting state is intentional and documented. Passes when every workaround is either justified-and-kept or removed-because-fixed, with rationale recorded.

**Acceptance Scenarios**:

1. **Given** the `SyncMetadata` workaround documented as revertible once TIKA-4126 is resolved, **When** 3.3.1 is confirmed to include the fix, **Then** the workaround is reverted; **otherwise** it is retained with a note that it is still required.
2. **Given** the locally patched `tika-core` (`2.4.0-p1`), **When** the upgrade is performed, **Then** the project either consumes upstream 3.3.1 directly or carries forward only the patches still not available upstream, and the decision is recorded.

---

### Edge Cases

- **Relocated/renamed/removed parsers**: Tika 3.x reorganized parser packages and dropped some parsers. Any IPED reference to a moved or removed parser must be remapped or replaced so the corresponding format keeps being handled.
- **Detection differences**: A media type that Tika 2.4.0 detected one way may be detected differently (or not at all) in 3.3.1, which can reshape the category tree or routing of items.
- **Extraction differences**: Differences in extracted text or metadata values could ripple into downstream tasks (text hashing, regex/validators, NER, language detection) and change their output.
- **Transitive dependency conflicts**: Tika 3.x may require newer versions of shared libraries (office/PDF/compression) that conflict with versions IPED pins elsewhere.
- **Custom configuration validity**: IPED's MIME-type, parser, and custom-signature configuration referencing Tika types/classes must remain valid against the 3.x model.
- **Case/index backward compatibility**: Field keys and analyzer behavior derived from Tika metadata must not change in a way that breaks searching previously processed cases.
- **Performance/memory**: A behavioral change in a heavily used parser must not regress throughput or peak memory beyond the SC-007 budget (≤10% each) on large cases.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: IPED MUST use Apache Tika 3.3.1 for content type detection and text/metadata extraction, replacing the current 2.4.0 / `2.4.0-p1` dependency.
- **FR-002**: Every evidence/file format that IPED currently detects, parses, and indexes MUST continue to be detected, parsed, and indexed after the upgrade — no loss of supported-format coverage.
- **FR-003**: All IPED custom parsers built on top of Tika MUST continue to function and produce output equivalent to the pre-upgrade behavior.
- **FR-004**: Indexed field keys and metadata semantics derived from Tika MUST remain stable so that previously processed cases stay searchable and viewable; any unavoidable change MUST be documented and mapped.
- **FR-005**: The full multi-module project MUST compile, build, and produce the packaged release without Tika-related errors.
- **FR-006**: The existing automated test suite MUST pass at a rate equal to or better than the pre-upgrade baseline; tests broken solely by Tika 3.x API/package changes MUST be migrated to the new API while preserving their original assertions and intent.
- **FR-007**: IPED-specific Tika patches and workarounds MUST be reconciled with 3.3.1 — removed if the underlying issue is fixed upstream (e.g., the `SyncMetadata` workaround for TIKA-4126), otherwise carried forward with recorded justification.
- **FR-008**: IPED configuration that references Tika (MIME types, parser configuration, custom signatures, category mappings) MUST remain valid or be updated to the Tika 3.x model.
- **FR-009**: Shared/transitive dependencies (e.g., PDFBox, POI, and other libraries Tika builds on) MUST be aligned **up** to the versions Tika 3.3.1 expects rather than held back; IPED code that directly uses the older APIs of those libraries MUST be migrated to the new APIs so that no existing feature breaks.
- **FR-010**: The decision on whether to consume upstream Tika 3.3.1 directly or to maintain an IPED-patched build MUST be made explicitly and recorded, along with the list of any patches carried forward.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The set of file formats successfully detected and parsed after the upgrade is equal to or a superset of the pre-upgrade set — zero supported formats regress to unsupported on the benchmark dataset.
- **SC-002**: Processing the agreed benchmark case before and after the upgrade yields equivalent results — top-level (filesystem-derived) item counts match the baseline **exactly**, Tika-produced subitem counts match within **±1%**, and **at least 99%** of items have equivalent extracted text and key metadata; the remaining **<1%** are manually reviewed and each difference is justified.
- **SC-003**: The number of processing errors/failures on the benchmark dataset after the upgrade is less than or equal to the number before it.
- **SC-004**: The automated test suite passes at a rate equal to or better than the pre-upgrade baseline, with no newly failing test left unexplained.
- **SC-005**: A clean build produces a working, runnable release package, and a previously processed case opens, searches, and displays correctly in it.
- **SC-006**: Every pre-existing IPED Tika patch/workaround has a recorded disposition (kept-with-justification or removed-because-fixed) after the upgrade — none left in an undecided state.
- **SC-007**: Processing throughput regresses by no more than **10%** and peak memory increases by no more than **10%** on the benchmark case, measured against the pre-upgrade baseline.

## Assumptions

- **Target version**: The target is exactly Apache Tika **3.3.1**.
- **Parity, not expansion**: This is a maintenance upgrade aimed at behavioral parity on the new version. Adopting net-new Tika 3.x features/parsers is out of scope and deferred to follow-up work, except where required to preserve an existing capability.
- **Java baseline unchanged**: IPED stays on Java 11, which is compatible with the Tika 3.x runtime requirement; no JDK upgrade is bundled into this effort.
- **Regression gate**: The existing automated test suite plus a curated multi-format benchmark case constitute the regression gate. The benchmark case covers IPED's high-traffic categories — documents, email, archives, images, SQLite/chat apps, registry, PDF, and office — and is assembled (not a real sensitive case) so it can be shared and re-run. A formal, exhaustive per-format conformance suite beyond what exists today is not assumed to be created here.
- **Patched fork starting point**: IPED currently consumes a locally patched `tika-core` (`2.4.0-p1`). The default approach is to adopt upstream 3.3.1 and re-apply only patches still required and not yet available upstream; the final approach is recorded per FR-010.
- **Tolerated differences**: Minor, documented and justified differences in extracted output are acceptable provided they do not reduce supported-format coverage, break case/index compatibility, or break downstream tasks.
- **Count tolerance**: Top-level item counts are Tika-independent (driven by the Sleuthkit/data-source readers) and must match the baseline exactly; only Tika-produced subitem counts may vary, bounded at ±1% and triaged (SC-002).
- **Case compatibility scope**: "Backward compatibility" means existing case indexes remain readable and searchable; it does not require byte-for-byte identical re-extraction of older cases.
