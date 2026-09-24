package pt.planet.importfile;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class ValidationResult {

    public record ValidationError(String fieldName, String errorMessage) {}

    private final List<ValidationError> errors = new ArrayList<>();

    public void addError(String fieldName, String errorMessage) {
        errors.add(new ValidationError(fieldName, errorMessage));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

}
