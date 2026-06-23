# Contract: Tika Metadata → Lucene Field Stability

**Feature**: [spec.md](../spec.md) · **Type**: Index/case backward-compatibility contract · **Date**: 2026-06-23

IPED maps Tika `Metadata` properties into Lucene index fields (`IndexItem`/`BasicProps`, via `MetadataUtil`). Existing cases store these field **names**; renaming any of them breaks search on previously processed cases (CLAUDE.md iped-engine §18). This contract pins what MUST stay stable across the Tika 3.x metadata-constant relocations (FR-004, SC-005).

## Invariants (MUST hold)

1. **Field keys unchanged**: The string keys of every Lucene field derived from Tika metadata are byte-identical before and after the upgrade. No field is renamed, added-as-replacement, or dropped as a side effect of a Tika constant moving.
2. **Analyzer behavior unchanged**: `AppAnalyzer` per-field treatment (lowercase, ASCII-fold, tokenization, keyword vs. text) is untouched.
3. **Old cases readable**: A case processed on the 2.4.0 build opens, searches, and displays correctly on the 3.3.1 build (SC-005).
4. **Property → key mapping preserved**: Where Tika renames a metadata property/constant, IPED maps the new property to the **existing** field key (a guard), rather than letting the index field follow Tika's new name.

## Status of metadata symbols at 3.3.1 (🔁 verified T004, 2026-06-23)

The symbol-relocation concern is **moot for IPED**: IPED already references `TikaCoreProperties.RESOURCE_NAME_KEY` (128×), which is **present** in tika-core 3.3.1; `org.apache.tika.metadata.TikaMetadataKeys` and `org.apache.tika.io.IOUtils` have **0 IPED usages** (and `io.IOUtils` is still present anyway). So there is **no metadata-symbol re-import work**.

**The live invariant is the field-key stability guard (D7)** — independent of symbol locations. Even with stable symbols, a Tika 3.x **parser** can emit a metadata **property value** differently than 2.4 did; if such a property currently feeds a stable Lucene field, IPED must normalize it so the **field key the index sees is unchanged**:

**Migration (research D7)**:
- For any metadata property whose emitted value/name changes upstream and currently feeds a stable Lucene field, add an explicit normalization in `MetadataUtil`/`IndexItem` so the **field key is unchanged**.
- Detect such cases by diffing the indexed field-key set baseline-vs-upgraded on the benchmark (acceptance test below).

### Contract test (acceptance)

- **Regression set**: Build a small case on 2.4.0, record the full field-key set (e.g., dump index field names). After upgrade, re-open the **same** case and assert the field-key set is identical and queries still match (SC-005).
- **New-vs-old**: Process the benchmark on both builds; diff the set of indexed field keys — it MUST be identical (any new field must be an explicit, justified addition, not an accidental rename).

## Out of scope

- Indexing new metadata that Tika 3.x newly emits (parity-not-expansion); if observed, it is documented, not auto-indexed under a new field, unless explicitly chosen.
