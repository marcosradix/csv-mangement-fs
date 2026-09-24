package pt.planet.importfile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerValidatorTest {

    private CustomerValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CustomerValidator();
    }

    @Test
    @DisplayName("Should validate correct customer record")
    void testValidCustomer() {
        ValidationResult result = validator.validate("1", "John Smith", "john@example.com", "35", "Portugal",
                "+351910000000");
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("Should validate customer with null optional fields (email, country, phone)")
    void testValidCustomerNullOptionalFields() {
        ValidationResult result = validator.validate("4", "Ana Costa", null, "25", null, null);
        assertThat(result.isValid()).isTrue();

        ValidationResult resultEmpty = validator.validate("4", "Ana Costa", "", "25", "", "");
        assertThat(resultEmpty.isValid()).isTrue();
    }

    @Test
    @DisplayName("Should reject null, empty, or blank age and record error without stopping")
    void testRejectNullOrEmptyAge() {
        ValidationResult nullAge = validator.validate("1", "John", "john@example.com", null, "Portugal", null);
        assertThat(nullAge.isValid()).isFalse();
        assertThat(nullAge.getErrors()).anyMatch(e -> e.fieldName().equals("age") && e.errorMessage().contains("cannot be null or empty"));

        ValidationResult emptyAge = validator.validate("1", "John", "john@example.com", "", "Portugal", null);
        assertThat(emptyAge.isValid()).isFalse();
        assertThat(emptyAge.getErrors()).anyMatch(e -> e.fieldName().equals("age") && e.errorMessage().contains("cannot be null or empty"));

        ValidationResult blankAge = validator.validate("1", "John", "john@example.com", "   ", "Portugal", null);
        assertThat(blankAge.isValid()).isFalse();
        assertThat(blankAge.getErrors()).anyMatch(e -> e.fieldName().equals("age") && e.errorMessage().contains("cannot be null or empty"));
    }

    @Test
    @DisplayName("Should reject missing or invalid ID")
    void testInvalidId() {
        ValidationResult nullId = validator.validate(null, "John", "john@example.com", "30", "Portugal", null);
        assertThat(nullId.isValid()).isFalse();
        assertThat(nullId.getErrors()).anyMatch(e -> e.fieldName().equals("id"));

        ValidationResult blankId = validator.validate("   ", "John", "john@example.com", "30", "Portugal", null);
        assertThat(blankId.isValid()).isFalse();

        ValidationResult negativeId = validator.validate("-5", "John", "john@example.com", "30", "Portugal", null);
        assertThat(negativeId.isValid()).isFalse();

        ValidationResult nonNumericId = validator.validate("abc", "John", "john@example.com", "30", "Portugal", null);
        assertThat(nonNumericId.isValid()).isFalse();
    }

    @Test
    @DisplayName("Should reject missing or blank name")
    void testInvalidName() {
        ValidationResult nullName = validator.validate("1", null, "john@example.com", "30", "Portugal", null);
        assertThat(nullName.isValid()).isFalse();
        assertThat(nullName.getErrors()).anyMatch(e -> e.fieldName().equals("name"));

        ValidationResult blankName = validator.validate("1", "   ", "john@example.com", "30", "Portugal", null);
        assertThat(blankName.isValid()).isFalse();
    }

    @Test
    @DisplayName("Should reject invalid email format like 'marco@example' without domain TLD")
    void testInvalidEmail() {
        ValidationResult result = validator.validate("5", "Marco Rossi", "marco@example", "30", "Italy",
                "+39000000000");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .anyMatch(e -> e.fieldName().equals("email") && e.errorMessage().contains("invalid format"));
    }

    @Test
    @DisplayName("Should reject non-numeric age like 'thirty'")
    void testInvalidAgeText() {
        ValidationResult result = validator.validate("5", "Marco Rossi", "marco@example.com", "thirty", "Italy",
                "+39000000000");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .anyMatch(e -> e.fieldName().equals("age") && e.errorMessage().contains("valid integer"));
    }

    @Test
    @DisplayName("Should reject age outside bounds (e.g. -5, 200)")
    void testAgeOutOfBounds() {
        ValidationResult negative = validator.validate("1", "John", "john@example.com", "-5", "Portugal", null);
        assertThat(negative.isValid()).isFalse();

        ValidationResult excessive = validator.validate("1", "John", "john@example.com", "200", "Portugal", null);
        assertThat(excessive.isValid()).isFalse();
    }
}
