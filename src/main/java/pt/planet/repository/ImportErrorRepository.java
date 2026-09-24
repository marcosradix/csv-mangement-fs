package pt.planet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pt.planet.domain.ImportErrorEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportErrorRepository extends JpaRepository<ImportErrorEntity, Long> {
    List<ImportErrorEntity> findByImportIdOrderByRowNumberAsc(UUID importId);
}
