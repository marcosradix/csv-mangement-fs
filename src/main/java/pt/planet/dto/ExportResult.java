package pt.planet.dto;

import java.util.Arrays;
import java.util.Objects;

/**
 * Data transfer object encapsulating the result of a customer export operation.
 *
 * @param data        binary content of the exported file
 * @param contentType MIME type of the exported format
 * @param filename    generated filename including timestamp and extension
 */
public record ExportResult(
        byte[] data,
        String contentType,
        String filename
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExportResult that)) return false;
        return Arrays.equals(data, that.data) &&
                Objects.equals(contentType, that.contentType) &&
                Objects.equals(filename, that.filename);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(contentType, filename);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "ExportResult[contentType=" + contentType +
                ", filename=" + filename +
                ", dataLength=" + (data != null ? data.length : 0) + "]";
    }
}
