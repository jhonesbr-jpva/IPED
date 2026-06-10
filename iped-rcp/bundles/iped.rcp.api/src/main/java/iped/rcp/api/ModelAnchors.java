package iped.rcp.api;

/**
 * Stable e4 model element ids where third-party model fragments may
 * contribute parts. ONLY the ids listed here are contract; any other id of
 * the product's application model may change without notice.
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public final class ModelAnchors {

    /** Central editor-like area (results table, gallery, content viewers). */
    public static final String PART_STACK_CENTER = "iped.rcp.app.partstack.center";

    /** Left navigation area (evidence/category/bookmark trees). */
    public static final String PART_STACK_LEFT = "iped.rcp.app.partstack.left";

    /** Right detail area (metadata, filters). */
    public static final String PART_STACK_RIGHT = "iped.rcp.app.partstack.right";

    /** Bottom auxiliary area (aux tables, specialized views). */
    public static final String PART_STACK_BOTTOM = "iped.rcp.app.partstack.bottom";

    private ModelAnchors() {
    }
}
