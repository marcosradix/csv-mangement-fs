package pt.planet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pt.planet.domain.ImportEntity;

import java.util.UUID;

@Repository
public interface ImportRepository extends JpaRepository<ImportEntity, UUID> {
}
