package pt.planet.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import pt.planet.api.ExportsApi;
import pt.planet.dto.ExportRequest;
import pt.planet.dto.ExportResult;
import pt.planet.service.ExportService;

@RestController
@RequiredArgsConstructor
public class ExportController implements ExportsApi {

    private final ExportService exportService;

    @Override
    public ResponseEntity<Resource> exportData(ExportRequest exportRequest) {
        ExportResult result = exportService.exportCustomers(exportRequest);

        ByteArrayResource resource = new ByteArrayResource(result.data());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .contentType(MediaType.parseMediaType(result.contentType()))
                .contentLength(result.data().length)
                .body(resource);
    }
}
