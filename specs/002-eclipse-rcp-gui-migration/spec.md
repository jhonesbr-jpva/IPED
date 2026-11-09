# Feature Specification: Eclipse RCP GUI Migration

**Feature Branch**: `002-eclipse-rcp-gui-migration`

**Created**: 2026-05-29

**Status**: Draft

**Input**: User description: "Vamos migrar a GUI do IPED para Eclipse RCP."

## Overview

The IPED case-analysis interface (the desktop application a forensic examiner opens
to explore a processed case) is today a large, tightly-coupled Swing/JavaFX
application built around a single main window with a custom docking system,
custom theming, and custom layout persistence. This feature moves that analysis
interface onto the **Eclipse Rich Client Platform (RCP)** workbench model so the
application gains a mature, standardized foundation for docking/perspectives,
plugin-based extensibility, preferences, and layout management — **without changing
what the examiner can do or the forensic results they see**.

The migration concerns the **analysis / case-review GUI only**. The case
**processing** pipeline (ingestion, parsing, indexing, carving, reporting tasks)
and its command-line entry point are out of scope and MUST remain compatible and
unchanged.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Open and analyze a case in the new workbench (Priority: P1)

A forensic examiner opens a previously processed case and works through it: they
see the results table, navigate the filesystem/categories/bookmarks/AI trees, run
keyword and structured filter searches, inspect the metadata of a selected item,
and preview the item's content in the correct viewer (text, image, PDF, HTML,
e-mail, hex fallback, etc.). Everything returns the same items, counts, and
rendered content as the current application, but inside the Eclipse RCP workbench.

**Why this priority**: This is the irreducible core of the product. An examiner
who can open a case, search, navigate, read metadata, and preview content has a
viable analysis tool. Without this, nothing else matters. It is the migration's MVP.

**Independent Test**: Take a real processed case, open it in the migrated GUI,
run a keyword search, select results across each navigation tree, and preview at
least one item per major content type. Verify identical result counts and viewer
output versus the current GUI.

**Acceptance Scenarios**:

1. **Given** a case processed by the current IPED engine, **When** the examiner launches the analysis GUI pointing at that case, **Then** the workbench opens showing the results table populated with the same total item count as the current GUI.
2. **Given** an open case, **When** the examiner types a keyword query and runs it, **Then** the same set of matching items is returned and highlighted as in the current GUI.
3. **Given** an open case, **When** the examiner selects an item, **Then** its metadata panel and content preview show the same data and rendering as the current GUI for that item's content type.
4. **Given** an open case, **When** the examiner navigates the filesystem, categories, bookmarks, and AI-filter trees, **Then** each tree shows the same hierarchy and selecting a node filters the results identically to the current GUI.

---

### User Story 2 - Visual triage: gallery, bookmarks, columns, similarity search (Priority: P2)

An examiner triages a large set of items visually: browses the image/video
gallery, applies and reorders result columns, tags items into bookmarks, and runs
similar-image / similar-face / similar-document / duplicate searches to expand or
narrow the working set.

**Why this priority**: Visual triage and bookmarking are the day-to-day workflow
for high-volume cases (e.g. CSAM triage), but they build on the P1 core and are
not required for the most basic analysis session.

**Independent Test**: With a case containing images, switch to the gallery, tag a
selection into a bookmark, run a similar-image search from a seed item, and
confirm the bookmark and similarity results match the current GUI.

**Acceptance Scenarios**:

1. **Given** an open case with images, **When** the examiner switches to the gallery, **Then** thumbnails render and paginate as in the current GUI.
2. **Given** a selection of items, **When** the examiner assigns them to a bookmark, **Then** the bookmark tree updates and the assignment persists in the case exactly as the current GUI writes it.
3. **Given** a seed image, **When** the examiner runs a similar-image (or similar-face) search, **Then** the same ranked candidate set is returned as in the current GUI.
4. **Given** the results table, **When** the examiner adds, removes, or reorders columns, **Then** the layout reflects the change and the selection persists across sessions.

---

### User Story 3 - Advanced analysis: communications graph, timeline, map (Priority: P2)

An examiner explores relationships and temporal/geographic patterns: the
communications graph (link analysis), the timeline of events, and the geographic
map of georeferenced items.

**Why this priority**: These specialized views deliver high investigative value
but are used by a subset of examiners on a subset of cases; they can follow the
core and triage capabilities.

**Independent Test**: On a case with communications, timestamps, and geolocation,
open each of the graph, timeline, and map views and confirm they render and
interact (expand nodes, zoom timeline, pan map) equivalently to the current GUI.

**Acceptance Scenarios**:

1. **Given** a case with communication relationships, **When** the examiner opens the graph view and expands a node, **Then** the same neighbors and edges appear as in the current GUI.
2. **Given** a case with timestamped events, **When** the examiner opens the timeline, **Then** the same event distribution is plotted and the same items are selected when a time range is chosen.
3. **Given** a case with georeferenced items, **When** the examiner opens the map, **Then** the same markers appear and selecting a marker filters the results identically.

---

### User Story 4 - Reporting and export (Priority: P3)

An examiner exports selected items, generates an HTML report, and produces a
portable case for sharing with other investigators.

**Why this priority**: Reporting/export is essential to deliver casework, but it
sits at the end of the analysis workflow and depends on the bookmarking and
selection capabilities established earlier.

**Independent Test**: Bookmark a set of items, generate an HTML report and a
portable case, and confirm the outputs are equivalent to those produced by the
current GUI.

**Acceptance Scenarios**:

1. **Given** a bookmarked selection, **When** the examiner exports the selected files, **Then** the exported files and folder structure match the current GUI's output.
2. **Given** a bookmarked selection, **When** the examiner generates an HTML report, **Then** the report content matches the current GUI's report.
3. **Given** an open case, **When** the examiner creates a portable case, **Then** the portable case opens correctly in both the current and the migrated GUI.

---

### User Story 5 - Personalize and persist the workbench (Priority: P3)

An examiner arranges panels into a layout that suits their workflow, switches
between light and dark themes, adjusts interface scaling for their display,
changes the interface language, and finds their arrangement restored the next time
they open the application.

**Why this priority**: Ergonomics and persistence are important for adoption and
long sessions, but the tool is usable with a default layout; this can be delivered
after functional parity.

**Independent Test**: Rearrange panels, switch theme and language, close and
reopen the application, and confirm the layout, theme, and language are restored.

**Acceptance Scenarios**:

1. **Given** a custom panel arrangement, **When** the examiner closes and reopens the application, **Then** the arrangement is restored.
2. **Given** the application open, **When** the examiner switches between light and dark themes, **Then** all panels and viewers update with adequate contrast.
3. **Given** the application open, **When** the examiner changes the interface language (e.g., between PT-BR and EN), **Then** all menus, labels, and tooltips switch to the selected language.
4. **Given** a high-DPI display, **When** the examiner sets the interface scaling, **Then** text and controls scale legibly across all panels.

---

### User Story 6 - Extend the GUI via documented extension points (Priority: P3)

A plugin/extension developer adds a new content viewer, results panel, or
context-menu action to the workbench using documented extension points, without
modifying core application classes, and drops the extension into the existing
plugin mechanism.

**Why this priority**: Extensibility is a long-term maintainability and ecosystem
goal aligned with the project's "extend, don't modify" principle, but no examiner
workflow depends on it for the initial release.

**Independent Test**: Build a sample extension that registers a new viewer for a
custom content type, drop it into the plugin location, launch the GUI, and confirm
the viewer is offered for that content type without any change to core classes.

**Acceptance Scenarios**:

1. **Given** a sample viewer extension, **When** it is installed via the plugin mechanism, **Then** the workbench offers it for its declared content types without core code changes.
2. **Given** the existing custom viewers (e.g., those resolving chat attachments), **When** the migrated GUI runs, **Then** they continue to function through the supported extension mechanism.

---

### Edge Cases

- **Old persisted layouts**: A user upgrading from the current (custom-docking) GUI has a saved layout in their profile. The migrated GUI MUST start with a valid default layout and MUST NOT crash or hang on an incompatible legacy layout file.
- **Headless / no-display environment**: When no graphical display or no rich-rendering runtime is available, content types normally shown by display-dependent viewers MUST degrade gracefully (informative fallback) instead of failing the whole application, mirroring today's behavior.
- **Very large cases**: With cases of hundreds of millions of items, the interface MUST remain responsive (no UI-thread blocking during search, gallery scroll, or tree expansion).
- **Opening a case still being processed (interactive mode)**: The GUI MUST support opening/refreshing a case that is concurrently being processed, as the current GUI does.
- **Multi-case sessions**: Opening multiple cases at once MUST present a unified, searchable view as today.
- **Missing external tools/models**: When an optional native tool, AI model, or external viewer is absent, the dependent feature MUST disable or warn gracefully rather than crash.
- **Locale with missing translations**: If a label lacks a translation in the chosen language, the interface MUST fall back to the default language rather than showing a blank or raw key string.
- **Long-running viewers**: A heavy viewer (e.g., office-document rendering) that hangs or crashes MUST NOT take down the whole workbench.

## Requirements *(mandatory)*

### Functional Requirements

**Functional parity (no regression)**

- **FR-001**: The migrated analysis GUI MUST provide every analysis capability available in the current GUI, including: results table with auxiliary tables (sub-items, parent, duplicates, references, referenced-by); image/video gallery; filesystem, categories, bookmarks, and AI-filter navigation trees; metadata panel; saved/active filters panel; evidence panel; keyword and structured search; similar-image, similar-face, similar-document, and duplicate searches; content viewers for all currently supported content types; communications graph; timeline; geographic map; bookmarking/tagging; column management; export; HTML report generation; portable case creation; multi-case sessions; and interactive (process-while-viewing) mode.
- **FR-002**: For any given case and user action, the migrated GUI MUST return the same items, counts, ordering, and rendered content as the current GUI. Any intentional difference MUST be documented and approved.
- **FR-003**: The migrated GUI MUST open cases produced by the current IPED engine without requiring re-processing, and MUST NOT alter the on-disk case format, index, or bookmark storage in any way that prevents the case from being reopened by the current GUI or other IPED tools.

**Forensic integrity & determinism (Constitution Principle IV)**

- **FR-004**: The migrated GUI MUST NOT alter item identity, hashing, ordering, or any value derived from the case; it is a read/annotate surface and MUST preserve the chain-of-custody guarantees of the current GUI.
- **FR-005**: All text handling in the migrated GUI MUST use explicit character encodings consistent with the rest of IPED (UTF-8 default), so item names and content render identically to today.
- **FR-006**: Bookmark/tag annotations created or modified in the migrated GUI MUST be written in the same format as the current GUI so annotations remain interoperable between GUIs.

**Localization, theming, scaling, layout (Constitution Principle III)**

- **FR-007**: The migrated GUI MUST be fully localized in at least PT-BR and EN, and MUST preserve the currently shipped locales (including es_AR, de_DE, fr_FR, it_IT), with all user-visible strings sourced from localization bundles rather than hardcoded.
- **FR-008**: The migrated GUI MUST allow the user to switch the interface language, falling back to the default language for any missing translation.
- **FR-009**: The migrated GUI MUST provide light and dark themes with adequate contrast across all panels and viewers.
- **FR-010**: The migrated GUI MUST support display (DPI) scaling configurable by the user.
- **FR-011**: The migrated GUI MUST let the user rearrange/dock panels and MUST persist and restore that layout across sessions; it MUST start from a valid default layout when no compatible saved layout exists (including when only a legacy layout is present).

**Launch & process model (Constitution Principle V)**

- **FR-012**: The migrated analysis GUI MUST be launchable from the existing release in a manner equivalent to today (the user starts the analysis application against a case directory or multi-case list), and MUST preserve the out-of-process isolation model so a GUI crash does not corrupt the case.
- **FR-013**: The case **processing** pipeline and its command-line entry point MUST remain unchanged by this feature; only the analysis GUI is migrated.
- **FR-014**: The migrated GUI MUST keep long-running and crash-prone work (search, I/O, content rendering, external/native viewers) off the UI thread and isolated, so the interface stays responsive and a single failing item or viewer does not bring down the workbench.

**Extensibility (Constitution Principles I & II)**

- **FR-015**: The migrated GUI MUST allow new viewers, panels, and actions to be added as separate extensions rather than by modifying core classes, exposed through a documented extension mechanism.
- **FR-016**: Existing GUI extension contracts consumed by plugins and custom viewers (the viewer contract, the result-set viewer contract, the GUI-provider contract, and plugin/script UI hooks) MUST keep working through a **compatibility adapter layer** that wraps the legacy contracts over the new RCP-native extension points. Existing third-party plugins and custom viewers MUST continue to function without being rewritten; new extensions MAY target the RCP-native extension points directly. The adapter layer MUST be documented for both legacy and new authors.
- **FR-017**: Plugins and AI models currently dropped into the release's plugin/model locations MUST continue to be discovered and loaded by the migrated GUI.

**Migration scope & coexistence**

- **FR-018**: The migration MUST follow an **incremental path with a full-replacement end state**: during the transition both the current GUI and the migrated GUI are shipped side-by-side and the examiner can choose either; once the migrated GUI reaches full parity (SC-001), the current Swing/JavaFX analysis GUI is retired and removed. The release MUST clearly indicate which GUI is the default at each stage.
- **FR-019**: The migrated GUI's minimum Java runtime MAY be raised above the project's current Java 11 baseline to whatever the targeted Eclipse RCP platform version requires (e.g., Java 17+), and the release MUST bundle a runtime satisfying that requirement. This raises the runtime **for the analysis GUI**; the processing pipeline's runtime constraint is governed separately (FR-013). This change requires a corresponding amendment to the project constitution's build/runtime constraints before the migrated GUI is released.

**Accessibility & usability**

- **FR-020**: The migrated GUI MUST preserve keyboard navigation and shortcuts for the primary analysis workflows at least equivalent to the current GUI.
- **FR-021**: The migrated GUI MUST provide user feedback (progress, busy indicators, startup/splash experience) for long operations equivalent to the current GUI.

### Key Entities *(include if feature involves data)*

- **Case**: A processed evidence set (search index + data + bookmarks) opened for analysis. The GUI reads it and writes only annotations. One or many may be open (multi-case).
- **Item**: The unit of analysis (file, record, message, carved item). The GUI displays, previews, filters, and annotates items but never mutates their forensic content.
- **Bookmark/Tag**: An examiner-applied marker on items, persisted in the case in the existing interoperable format.
- **Filter / Search**: A query or structured filter (including similarity and duplicate filters) that narrows the result set; filters can be combined and saved.
- **Viewer**: A rendering strategy for a set of content types; the workbench selects the appropriate viewer for the selected item and supports specialized views (graph, timeline, map).
- **Perspective / Layout**: The arrangement of panels/views the examiner works in, persisted per user and restorable across sessions.
- **Extension / Plugin**: An externally provided contribution (viewer, panel, action, model) discovered at runtime and surfaced in the workbench without core code changes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the analysis capabilities enumerated for the current GUI are available in the migrated GUI before the current GUI is retired (feature-parity checklist fully checked).
- **SC-002**: For a defined regression suite of cases and workflows, the migrated GUI returns identical result counts, ordering, and viewer output to the current GUI in 100% of compared scenarios (zero unexplained discrepancies).
- **SC-003**: 100% of cases in the regression suite that open in the current GUI also open in the migrated GUI without re-processing, and annotations written by either GUI remain readable by the other.
- **SC-004**: Time to open a representative large case and time to run a representative keyword search in the migrated GUI are within 15% of the current GUI's times on the same hardware.
- **SC-005**: The interface remains responsive (no perceived freeze beyond 1 second) during search, gallery scrolling, and tree expansion on a case of at least 100 million items.
- **SC-006**: An examiner familiar with the current GUI can complete the core workflow (open case, search, navigate, preview, bookmark, export) in the migrated GUI on first attempt without external help, in time comparable to the current GUI.
- **SC-007**: All user-visible strings are available in at least PT-BR and EN, and switching language updates 100% of visible strings (with documented fallback for any gap).
- **SC-008**: A sample extension adds a new viewer to the workbench without modifying any core class, demonstrating the extension mechanism end-to-end.

## Assumptions

- **Scope**: Only the analysis/case-review GUI is migrated. The processing pipeline, its CLI, the Web API, and the hash-DB tool are out of scope and unchanged.
- **Target framework**: Eclipse RCP is the chosen target, as stated by the requester; this is treated as a fixed constraint, not a decision to revisit in this spec.
- **Parity first**: The guiding objective is behavioral and forensic parity with the current GUI; visual/ergonomic improvements beyond parity are explicitly out of scope for this feature and may be proposed separately.
- **Case compatibility is mandatory**: Per the project constitution (public-API and index stability), the on-disk case format, index schema, and bookmark storage are immutable for this feature.
- **Rendering technologies inside the workbench**: Where current viewers rely on specific rendering technologies, those may be retained, embedded, or re-implemented during the plan phase; the spec requires only equivalent output and graceful fallback, not a particular technique.
- **Processing-side scripts**: Custom processing tasks/parsers written as scripts are processing-side and unaffected; only GUI-facing extension points are in scope for FR-015/FR-016.
- **Default layout reset on upgrade**: Users upgrading from the current GUI may need to re-create custom layouts once; automatic migration of legacy layout files is not assumed.
- **Distribution model**: The migrated GUI continues to ship within the existing IPED release bundle (embedded runtime + external tools), not as a separately installed product. During the transition the bundle ships both GUIs side-by-side (FR-018).
- **Runtime & governance**: Raising the analysis GUI's minimum Java runtime above 11 (FR-019) is a deliberate, approved deviation from the current constitution baseline; the constitution's build/runtime section is to be amended (with version bump) before the migrated GUI is released. The processing pipeline's runtime is unaffected.
- **Extension compatibility**: A compatibility adapter layer (FR-016) is assumed feasible for the existing GUI extension contracts; if any specific legacy contract cannot be adapted, that gap is documented rather than silently dropping plugin support.
