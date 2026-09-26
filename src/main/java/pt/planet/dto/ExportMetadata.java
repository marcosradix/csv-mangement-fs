package pt.planet.dto;

/**
 * Data transfer object encapsulating metadata for a streamed customer export operation.
 *
 * @param contentType  MIME type of the exported format
 * @param filename     generated filename including timestamp and extension
 * @param totalRecords total count of customer records exported
 */
public record ExportMetadata(
        String contentType,
        String filename,
        int totalRecords
) {}
