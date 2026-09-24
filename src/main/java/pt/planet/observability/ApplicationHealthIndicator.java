package pt.planet.observability;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import pt.planet.repository.CustomerRepository;
import pt.planet.repository.ImportRepository;

@Component
@RequiredArgsConstructor
public class ApplicationHealthIndicator implements HealthIndicator {

    private final CustomerRepository customerRepository;
    private final ImportRepository importRepository;

    @Override
    public Health health() {
        try {
            long totalCustomers = customerRepository.count();
            long totalImports = importRepository.count();
            return Health.up()
                    .withDetail("service", "csv-management-fs")
                    .withDetail("status", "OPERATIONAL")
                    .withDetail("totalPersistedCustomers", totalCustomers)
                    .withDetail("totalExecutedImports", totalImports)
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("service", "csv-management-fs")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
