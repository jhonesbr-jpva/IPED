# Contract: Parser SPI & `AbstractParser` Migration

**Feature**: [spec.md](../spec.md) · **Type**: Internal extension contract (how IPED plugs into Tika) · **Date**: 2026-06-23

IPED extends Tika by implementing the `org.apache.tika.parser.Parser` SPI and registering parsers via `META-INF/services`. This contract defines what MUST stay invariant so custom parsers keep working (FR-003) after the Tika 3.x base-class changes.

## Invariants (MUST hold before and after upgrade)

1. **SPI surface**: Every IPED parser remains an `org.apache.tika.parser.Parser` exposing exactly:
   - `Set<MediaType> getSupportedTypes(ParseContext context)`
   - `void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)`
   The set of supported `MediaType`s declared by each parser is **unchanged** (same formats routed to the same parser).
2. **Registration**: `META-INF/services/org.apache.tika.parser.Parser` entries (and IPED's `StandardParser`/`AutoDetectParser` wiring) resolve to the same parser classes; no service entry is dropped.
3. **Embedded extraction**: Subitem generation via `EmbeddedDocumentExtractor.parseEmbedded(...)` keeps producing the same child items (CLAUDE.md §12.8).
4. **Behavior**: For a given input, the emitted `ContentHandler` text and `Metadata` keys/values are equivalent within the parity tolerance (SC-002).

## Status of the change at Tika 3.3.1 (🔁 verified T004, 2026-06-23)

`org.apache.tika.parser.AbstractParser` is **NOT removed** — it is **present in tika-core 3.3.1, marked `@Deprecated`**, still `implements Parser` with the convenience `parse(InputStream, ContentHandler, Metadata)` method intact (confirmed via `javap`). Therefore the **76 IPED subclasses compile unchanged** on 3.3.1; the contract above already holds with no migration.

**Implication**: The internal-shim migration (research D4) is **OPTIONAL cleanup** — undertaken only if/when IPED chooses to stop depending on the deprecated class — and is **not on the upgrade's critical path**. If pursued, the behavior-neutral shim + import-only swap is the approach:

```java
// OPTIONAL — iped-parsers-impl: iped/parsers/util/AbstractParser.java
package iped.parsers.util;
import java.io.Serializable;
import org.apache.tika.parser.Parser;
public abstract class AbstractParser implements Parser, Serializable {
    private static final long serialVersionUID = 1L;
}
```
```diff
- import org.apache.tika.parser.AbstractParser;
+ import iped.parsers.util.AbstractParser;
```

### Contract test (acceptance)

- **Given** the migrated parsers on Tika 3.3.1, **When** the existing per-parser JUnit suites run (`iped-parsers/iped-parsers-impl test`), **Then** they pass with their original assertions (FR-006), proving `getSupportedTypes`/`parse` behavior is preserved.
- **Given** a benchmark item of each supported format, **When** processed, **Then** it is routed to the same parser and yields equivalent output (SC-001, SC-002).

## Out of scope

- Adopting new Tika 3.x parser capabilities or new `Parser` default methods beyond what the removed base provided (spec: parity-not-expansion).
- Changing which formats any parser claims.
