package pt.planet.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import pt.planet.dto.ExportRequest;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BASE_PROBLEM_TYPE = "https://planet.pt/problems/";

    @ExceptionHandler(InvalidExportFormatException.class)
    public ProblemDetail handleInvalidExportFormat(InvalidExportFormatException ex, HttpServletRequest request) {
        log.warn("Invalid export format: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid Export Format");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-export-format"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(InvalidFileException.class)
    public ProblemDetail handleInvalidFile(InvalidFileException ex, HttpServletRequest request) {
        log.warn("Invalid file error: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid File");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-file"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(InvalidColumnException.class)
    public ProblemDetail handleInvalidColumn(InvalidColumnException ex, HttpServletRequest request) {
        log.warn("Invalid column requested: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid Column");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-column"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(InvalidImportRecordException.class)
    public ProblemDetail handleInvalidImportRecord(InvalidImportRecordException ex, HttpServletRequest request) {
        log.warn("Invalid import record: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid Import Record");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-import-record"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ProblemDetail handleCustomerNotFound(CustomerNotFoundException ex, HttpServletRequest request) {
        log.warn("Customer not found: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Resource Not Found");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "customer-not-found"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex,
            HttpServletRequest request) {
        log.warn("Concurrent update conflict detected: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(
                "Resource was updated concurrently by another operation. Please retry with the latest version.");
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "concurrent-update-conflict"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failure: {}", errors);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Request Validation Failed");
        problem.setDetail(errors.isEmpty() ? ex.getMessage() : errors);
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "validation-error"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Uploaded file exceeds limit: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Payload Too Large");
        problem.setDetail("Uploaded file exceeds maximum allowed size.");
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "file-size-exceeded"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(ImportProcessingException.class)
    public ProblemDetail handleImportProcessing(ImportProcessingException ex, HttpServletRequest request) {
        log.error("Import processing error", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Import Processing Error");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "import-processing-error"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed or unreadable request: {}", ex.getMessage());

        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            String fieldName = ife.getPath().stream()
                    .map(com.fasterxml.jackson.databind.JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));

            if ("format".equalsIgnoreCase(fieldName) ||
                    (ife.getTargetType() != null && ExportRequest.FormatEnum.class.isAssignableFrom(ife.getTargetType()))) {
                ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
                problem.setTitle("Invalid Export Format");
                problem.setDetail(String.format("Unsupported export format: '%s'. Supported formats are CSV, TXT, XLS, XLSX.", ife.getValue()));
                problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-export-format"));
                problem.setInstance(URI.create(request.getRequestURI()));
                problem.setProperty("timestamp", OffsetDateTime.now());
                return problem;
            }

            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            problem.setTitle("Invalid Request Body");
            problem.setDetail(String.format("Invalid value '%s' for field '%s'.", ife.getValue(), fieldName));
            problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-request-body"));
            problem.setInstance(URI.create(request.getRequestURI()));
            problem.setProperty("timestamp", OffsetDateTime.now());
            return problem;
        }

        Throwable mostSpecificCause = ex.getMostSpecificCause();
        if (mostSpecificCause instanceof IllegalArgumentException iae && iae.getMessage() != null && iae.getMessage().startsWith("Unexpected value '")) {
            String message = iae.getMessage();
            String value = message.substring(message.indexOf('\'') + 1, message.lastIndexOf('\''));
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            problem.setTitle("Invalid Export Format");
            problem.setDetail(String.format("Unsupported export format: '%s'. Supported formats are CSV, TXT, XLS, XLSX.", value));
            problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-export-format"));
            problem.setInstance(URI.create(request.getRequestURI()));
            problem.setProperty("timestamp", OffsetDateTime.now());
            return problem;
        }

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Malformed Request");
        problem.setDetail(mostSpecificCause != null && mostSpecificCause.getMessage() != null
                ? mostSpecificCause.getMessage()
                : "Malformed or unreadable request payload.");
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "malformed-request"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Invalid argument: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid Argument");
        problem.setDetail(ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "invalid-argument"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("Parameter type mismatch: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Type Mismatch");
        problem.setDetail(String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName()));
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "type-mismatch"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneralException(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error occurred", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("An unexpected internal error occurred: " + ex.getMessage());
        problem.setType(URI.create(BASE_PROBLEM_TYPE + "internal-error"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }
}
