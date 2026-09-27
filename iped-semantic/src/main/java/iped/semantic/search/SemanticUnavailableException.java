package iped.semantic.search;

/**
 * A question addressed to a modality that cannot answer it now. Carries the {@link Availability}
 * with the reason and what to do, for the UI to show and the MCP to return as a refusal. Never
 * answered by another model or another source (FR-044).
 */
public class SemanticUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Availability availability;

    public SemanticUnavailableException(Availability availability) {
        super(availability.getModality().key() + " semantic search unavailable: " + availability.getReason() + " - "
                + availability.getDetail());
        this.availability = availability;
    }

    public Availability getAvailability() {
        return availability;
    }
}
