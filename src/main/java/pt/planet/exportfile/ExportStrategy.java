package pt.planet.exportfile;

import pt.planet.domain.CustomerEntity;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;

public interface ExportStrategy {

    boolean supports(String format);

    /**
     * Streams customer export records directly to the provided OutputStream in batches
     * using the Keyset Pagination pattern to maintain low, constant memory consumption.
     *
     * @param customerSupplier supplier that fetches customer records in bounded keyset batches
     * @param columns          columns to export in order
     * @param outputStream     destination output stream for exported file content
     */
    void export(CustomerBatchSupplier customerSupplier, List<CustomerColumn> columns, OutputStream outputStream);

    /**
     * In-memory export method maintained for convenience and backward compatibility.
     * Delegates to the streaming export method via ByteArrayOutputStream.
     */
    default byte[] export(List<CustomerEntity> customers, List<CustomerColumn> columns) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        export(consumer -> {
            if (customers != null && !customers.isEmpty()) {
                consumer.accept(customers);
            }
        }, columns, out);
        return out.toByteArray();
    }

    String getContentType();

    String getFileExtension();
}
