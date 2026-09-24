package pt.planet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "import_errors")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ImportErrorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(name = "filename")
    private String filename;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "field_name")
    private String fieldName;

    @Column(name = "error_message", nullable = false, length = 500)
    private String errorMessage;

    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ImportErrorEntity(UUID importId, String filename, int rowNumber, String fieldName, String errorMessage, String rawData) {
        this.importId = importId;
        this.filename = filename;
        this.rowNumber = rowNumber;
        this.fieldName = fieldName;
        this.errorMessage = errorMessage;
        this.rawData = rawData;
    }

    public ImportErrorEntity(UUID importId, int rowNumber, String fieldName, String errorMessage, String rawData) {
        this(importId, null, rowNumber, fieldName, errorMessage, rawData);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImportErrorEntity other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
