package pt.planet.exportfile;

import pt.planet.domain.CustomerEntity;

import java.util.List;
import java.util.function.Consumer;

/**
 * Functional interface that supplies customer records in chunks/batches.
 * Used by export strategies to process data efficiently without loading the entire
 * table into memory at once (Keyset / Cursor-Based Pagination pattern).
 */
@FunctionalInterface
public interface CustomerBatchSupplier {

    /**
     * Traverses customer entities in batches, invoking the consumer for each batch.
     *
     * @param batchConsumer consumer that processes each batch of customer entities
     */
    void fetchBatches(Consumer<List<CustomerEntity>> batchConsumer);
}
