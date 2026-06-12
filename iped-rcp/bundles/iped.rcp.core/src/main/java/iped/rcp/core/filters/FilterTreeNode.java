package iped.rcp.core.filters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import iped.viewers.api.IFilter;

/**
 * Node of the combined filter tree (task T031, FR-016, data-model
 * "FilterState"): AND/OR groups with optional negation, mirroring the legacy
 * {@code filterdecisiontree} ({@code OperandNode}/{@code FilterNode}/
 * inverted flag). Leaves carry an engine-level {@link IFilter} — either an
 * {@code IQueryFilter} (evaluated as a query) or an {@code IResultSetFilter}
 * (evaluated against the match-all result), exactly like the legacy
 * {@code CombinedFilterer} bitset evaluation.
 *
 * <p>
 * Instances are mutated only by the filters panel on the UI thread;
 * {@link FilterStateService} snapshots the tree before evaluating it off the
 * UI thread.
 */
public final class FilterTreeNode {

    /** Group operator (legacy {@code OperandNode.Operand}). */
    public enum Operand {
        AND, OR
    }

    private final Operand operand; // null for leaves
    private final IFilter filter; // null for groups
    private final String label;
    private final List<FilterTreeNode> children = new ArrayList<>();
    private boolean negated;
    private FilterTreeNode parent;

    private FilterTreeNode(Operand operand, IFilter filter, String label) {
        this.operand = operand;
        this.filter = filter;
        this.label = label;
    }

    /** Creates an AND/OR group node. */
    public static FilterTreeNode group(Operand operand) {
        return new FilterTreeNode(operand, null, operand.name());
    }

    /** Creates a leaf carrying an engine filter. */
    public static FilterTreeNode leaf(IFilter filter, String label) {
        return new FilterTreeNode(null, filter, label);
    }

    public boolean isGroup() {
        return operand != null;
    }

    public Operand getOperand() {
        return operand;
    }

    public IFilter getFilter() {
        return filter;
    }

    public String getLabel() {
        return label;
    }

    public boolean isNegated() {
        return negated;
    }

    public void setNegated(boolean negated) {
        this.negated = negated;
    }

    public FilterTreeNode getParent() {
        return parent;
    }

    public List<FilterTreeNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public void add(FilterTreeNode child) {
        if (!isGroup()) {
            throw new IllegalStateException("Only group nodes accept children");
        }
        child.parent = this;
        children.add(child);
    }

    public void remove(FilterTreeNode child) {
        if (children.remove(child)) {
            child.parent = null;
        }
    }

    /** True when no leaf exists anywhere under this node (nothing to apply). */
    public boolean isEmpty() {
        if (!isGroup()) {
            return false;
        }
        for (FilterTreeNode child : children) {
            if (!child.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Deep copy, so evaluation works on an immutable snapshot. */
    public FilterTreeNode snapshot() {
        FilterTreeNode copy = new FilterTreeNode(operand, filter, label);
        copy.negated = negated;
        for (FilterTreeNode child : children) {
            copy.add(child.snapshot());
        }
        return copy;
    }

    @Override
    public String toString() {
        return (negated ? "NOT " : "") + (isGroup() ? operand.name() : label);
    }
}
