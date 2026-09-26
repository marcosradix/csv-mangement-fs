package pt.planet.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pt.planet.domain.CustomerEntity;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {

    List<CustomerEntity> findAllByOrderByIdAsc();

    /**
     * Retrieves the initial batch of customer records ordered by ID ascending.
     * Used as the first step of the Keyset (Cursor-Based) Pagination pattern.
     */
    List<CustomerEntity> findAllByOrderByIdAsc(Limit limit);

    /**
     * Retrieves the next batch of customer records where ID is greater than the given cursor.
     * Core query of the Keyset (Cursor-Based) Pagination pattern: uses primary key index seek O(log N).
     */
    List<CustomerEntity> findByIdGreaterThanOrderByIdAsc(Long id, Limit limit);
}
