package pt.planet.importfile;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class CustomerValidator {

    // RFC 5322 simplified email regex with required domain extension
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$");

    public ValidationResult validate(
            String rawId,
            String name,
            String email,
            String rawAge,
            String country,
            String phone) {
        ValidationResult result = new ValidationResult();

        // 1. Validate ID (required, positive Long)
        if (rawId == null || rawId.trim().isEmpty()) {
            result.addError("id", "Field 'id' is required");
        } else {
            try {
                long id = Long.parseLong(rawId.trim());
                if (id <= 0) {
                    result.addError("id", "Field 'id' must be a positive number");
                }
            } catch (NumberFormatException e) {
                result.addError("id", "Field 'id' must be a valid integer number, got: '" + rawId.trim() + "'");
            }
        }

        // 2. Validate Name (required, non-blank)
        if (name == null || name.trim().isEmpty()) {
            result.addError("name", "Field 'name' is required");
        } else if (name.trim().length() > 255) {
            result.addError("name", "Field 'name' must not exceed 255 characters");
        }

        // 3. Validate Email (optional, but if present must be valid format)
        if (email != null && !email.trim().isEmpty()) {
            String trimmedEmail = email.trim();
            if (trimmedEmail.length() > 255) {
                result.addError("email", "Field 'email' must not exceed 255 characters");
            } else if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
                result.addError("email", "Field 'email' has invalid format: '" + trimmedEmail + "'");
            }
        }

        // 4. Validate Age (cannot be null or empty, must be integer between 0 and 150)
        if (rawAge == null || rawAge.trim().isEmpty()) {
            result.addError("age", "Field 'age' cannot be null or empty");
        } else {
            String trimmedAge = rawAge.trim();
            try {
                int age = Integer.parseInt(trimmedAge);
                if (age < 0 || age > 150) {
                    result.addError("age", "Field 'age' must be between 0 and 150, got: " + age);
                }
            } catch (NumberFormatException e) {
                result.addError("age", "Field 'age' must be a valid integer, got: '" + trimmedAge + "'");
            }
        }

        // 5. Validate Country (optional, length <= 100)
        if (country != null && country.trim().length() > 100) {
            result.addError("country", "Field 'country' must not exceed 100 characters");
        }

        // 6. Validate Phone (optional, length <= 50)
        if (phone != null && phone.trim().length() > 50) {
            result.addError("phone", "Field 'phone' must not exceed 50 characters");
        }

        return result;
    }
}
