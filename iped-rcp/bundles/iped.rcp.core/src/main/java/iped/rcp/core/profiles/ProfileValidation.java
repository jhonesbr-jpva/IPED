package iped.rcp.core.profiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of validating a {@link ProfileConfigModel} before saving (feature 005,
 * US3, data-model §4). Errors block the save (incoherent types); warnings are
 * advisory (unknown keys). Validation never mutates the model and never touches
 * the base config files.
 *
 * @param errors   blocking problems (e.g. a BOOLEAN option set to a non-boolean)
 * @param warnings advisory notes (e.g. an overridden key absent from the base)
 */
public record ProfileValidation(List<String> errors, List<String> warnings) {

    public ProfileValidation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * Validates the overridden options of {@code model}: BOOLEAN values must be
     * {@code true}/{@code false}; INT values must parse as integers; overridden
     * keys absent from the base are warned about.
     */
    public static ProfileValidation validate(ProfileConfigModel model) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (model == null) {
            return new ProfileValidation(errors, warnings);
        }
        for (ConfigFileGroup group : model.groups()) {
            for (ConfigOption option : group.options()) {
                if (!option.overridden()) {
                    continue;
                }
                String value = option.effectiveValue();
                String loc = group.fileName() + " / " + option.key();
                switch (option.type()) {
                    case BOOLEAN -> {
                        if (value == null || !(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
                            errors.add(loc + ": expected true/false but was '" + value + "'");
                        }
                    }
                    case INT -> {
                        if (!isInteger(value)) {
                            errors.add(loc + ": expected an integer but was '" + value + "'");
                        }
                    }
                    default -> {
                        // TEXT/ENUM: no value-shape constraint
                    }
                }
                if (!option.isKnown()) {
                    warnings.add(loc + ": key is not present in the base config (unknown key)");
                }
            }
        }
        return new ProfileValidation(errors, warnings);
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
