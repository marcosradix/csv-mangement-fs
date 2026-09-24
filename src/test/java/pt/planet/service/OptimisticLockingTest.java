package pt.planet.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import pt.planet.Application;
import pt.planet.domain.CustomerEntity;
import pt.planet.repository.CustomerRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class OptimisticLockingTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Should detect concurrent modification and throw optimistic locking failure exception")
    void testOptimisticLockingConflict() {
        // Prepare customer
        Long customerId = 100L;
        transactionTemplate.execute(status -> {
            customerRepository.deleteById(customerId);
            CustomerEntity customer = new CustomerEntity(customerId, "Initial Name", "initial@example.com", 30, "Portugal", null);
            return customerRepository.save(customer);
        });

        // First transaction updates customer
        transactionTemplate.execute(status -> {
            CustomerEntity c = customerRepository.findById(customerId).orElseThrow();
            c.setName("Updated By Thread 1");
            return customerRepository.save(c);
        });

        // Verify version incremented
        CustomerEntity updated = customerRepository.findById(customerId).orElseThrow();
        assertThat(updated.getVersion()).isGreaterThan(0L);

        // Attempting to save an entity with stale version should fail
        assertThatThrownBy(() -> {
            transactionTemplate.execute(status -> {
                CustomerEntity stale = new CustomerEntity(customerId, "Stale Update", "stale@example.com", 30, "Portugal", null);
                stale.setVersion(0L); // Stale version
                return customerRepository.saveAndFlush(stale);
            });
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
