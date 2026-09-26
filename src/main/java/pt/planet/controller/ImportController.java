package pt.planet.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pt.planet.api.ImportsApi;
import pt.planet.dto.GroupedImportErrorResponse;
import pt.planet.dto.ImportResponse;
import pt.planet.service.ImportService;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ImportController implements ImportsApi {

    private final ImportService importService;

    @Override
    public ResponseEntity<List<ImportResponse>> importCsv(List<MultipartFile> files) {
        List<ImportResponse> responses = importService.processImports(files);

        return getResponse(responses);
    }

    private static ResponseEntity<List<ImportResponse>> getResponse(List<ImportResponse> responses) {
        boolean hasSuccess = responses.stream()
                .anyMatch(r -> r.getSuccessfulRecords() != null && r.getSuccessfulRecords() > 0);
        boolean hasErrors = responses.stream()
                .anyMatch(r -> (r.getFailedRecords() != null && r.getFailedRecords() > 0)
                        || r.getStatus() == ImportResponse.StatusEnum.FAILED
                        || r.getStatus() == ImportResponse.StatusEnum.PARTIAL_SUCCESS);

        if (!hasSuccess && hasErrors) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(responses);
        } else if (hasSuccess && hasErrors) {
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(responses);
        } else {
            return ResponseEntity.ok(responses);
        }
    }

    @Override
    public ResponseEntity<ImportResponse> getImportById(UUID importId) {
        ImportResponse response = importService.getImportById(importId);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<GroupedImportErrorResponse>> getImportErrors(UUID importId) {
        List<GroupedImportErrorResponse> errors = importService.getImportErrors(importId);
        return ResponseEntity.ok(errors);
    }
}
