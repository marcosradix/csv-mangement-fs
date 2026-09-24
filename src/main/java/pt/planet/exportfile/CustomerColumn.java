package pt.planet.exportfile;

import lombok.Getter;
import pt.planet.domain.CustomerEntity;
import pt.planet.exception.InvalidColumnException;

import java.util.Arrays;

@Getter
public enum CustomerColumn {
    ID("id") {
        @Override
        public String getValue(CustomerEntity customer) {
            return customer.getId() != null ? customer.getId().toString() : "";
        }
    },
    NAME("name") {
        @Override
        public String getValue(CustomerEntity customer) {
            return customer.getName() != null ? customer.getName() : "";
        }
    },
    EMAIL("email") {
        @Override
        public String getValue(CustomerEntity customer) {
            return customer.getEmail() != null ? customer.getEmail() : "";
        }
    },
    AGE("age") {
        @Override
        public String getValue(CustomerEntity customer) {
            return customer.getAge() != null ? customer.getAge().toString() : "";
        }
    },
    COUNTRY("country") {
        @Override
        public String getValue(CustomerEntity customer) {
            return customer.getCountry() != null ? customer.getCountry() : "";
        }
    },
    PHONE("phone") {
        @Override
        public String getValue(CustomerEntity customer) {
            return customer.getPhone() != null ? customer.getPhone() : "";
        }
    };

    private final String headerName;

    CustomerColumn(String headerName) {
        this.headerName = headerName;
    }

    public abstract String getValue(CustomerEntity customer);

    public static CustomerColumn fromString(String column) {
        if (column == null || column.trim().isEmpty()) {
            throw new InvalidColumnException("Column name cannot be null or empty");
        }
        String normalized = column.trim().toLowerCase();
        for (CustomerColumn col : values()) {
            if (col.headerName.equals(normalized) || col.name().equalsIgnoreCase(normalized)) {
                return col;
            }
        }
        throw new InvalidColumnException("Unsupported export column: '" + column + "'. Supported columns are: " +
                Arrays.toString(Arrays.stream(values()).map(CustomerColumn::getHeaderName).toArray()));
    }
}
