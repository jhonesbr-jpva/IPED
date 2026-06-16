package iped.rcp.core.processing;

/**
 * Tier-B (Common) creation toggles exposed on the wizard's <em>Common
 * options</em> page (feature 005, FR-007 / contracts/new-case-wizard.contract.md).
 * Each field maps to a boolean Bootstrap flag.
 *
 * @param addOwner            {@code --addowner} — index file owner of local folders
 * @param portable            {@code --portable} — relative references to images
 * @param noPstAttachs        {@code --nopstattachs} — do not export PST/OST attachments
 * @param noLinkedItems       {@code --nolinkeditems} — do not export chat-linked items
 * @param downloadInternetData {@code --downloadInternetData} — enrich from the Internet
 */
public record CommonOptions(boolean addOwner, boolean portable, boolean noPstAttachs,
        boolean noLinkedItems, boolean downloadInternetData) {

    /** All-false defaults (parity with the CLI when no flag is passed). */
    public static CommonOptions defaults() {
        return new CommonOptions(false, false, false, false, false);
    }
}
