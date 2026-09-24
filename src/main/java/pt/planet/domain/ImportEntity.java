package pt.planet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "imports")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ImportEntity {

    @Id
    private UUID id;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ImportStatus status;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "successful_records", nullable = false)
    private int successfulRecords;

    @Column(name = "failed_records", nullable = false)
    private int failedRecords;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ImportEntity(UUID id, String filename, ImportStatus status, int totalRecords, int successfulRecords, int failedRecords) {
        this.id = id;
        this.filename = filename;
        this.status = status;
        this.totalRecords = totalRecords;
        this.successfulRecords = successfulRecords;
        this.failedRecords = failedRecords;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImportEntity other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
