/**
 * Case creation and opening UI for the IPED RCP application (feature
 * 005-case-creation-wizard).
 *
 * <p>
 * This bundle hosts the SWT/JFace surface — the New Case wizard
 * ({@code iped.rcp.casecreation.wizard}), the profile manager/editor
 * ({@code iped.rcp.casecreation.profiles}) and the Open/New/Manage-Profiles
 * command handlers ({@code iped.rcp.casecreation.handlers}). The toolkit-free
 * logic it drives (request model, Bootstrap command building, out-of-process
 * launch and profile read/write) lives in {@code iped.rcp.core.processing} and
 * {@code iped.rcp.core.profiles} so it can be unit-tested without the e4
 * runtime.
 *
 * @apiNote Internal to the IPED RCP product; not part of the provisional
 *          extension API ({@code iped.rcp.api}).
 */
package iped.rcp.casecreation;
