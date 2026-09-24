package pt.planet.exportfile;

import pt.planet.domain.CustomerEntity;

import java.util.List;

public interface ExportStrategy {

    boolean supports(String format);

    byte[] export(List<CustomerEntity> customers, List<CustomerColumn> columns);

    String getContentType();

    String getFileExtension();
}
