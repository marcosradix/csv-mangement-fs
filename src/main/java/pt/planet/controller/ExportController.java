package pt.planet.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import pt.planet.api.ExportsApi;
import pt.planet.dto.ExportAsyncResponse;
import pt.planet.dto.ExportRequest;
import pt.planet.service.ExportService;

@RestController
@RequiredArgsConstructor
public class ExportController implements ExportsApi {

    private final ExportService exportService;

    @Override
    public ResponseEntity<ExportAsyncResponse> exportData(ExportRequest exportRequest, String xUserEmail) {
        ExportAsyncResponse response = exportService.processExportAsync(exportRequest, xUserEmail);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
