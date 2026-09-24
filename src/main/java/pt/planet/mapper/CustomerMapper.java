package pt.planet.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.data.domain.Page;
import pt.planet.domain.CustomerEntity;
import pt.planet.domain.ImportEntity;
import pt.planet.domain.ImportErrorEntity;
import pt.planet.domain.ImportStatus;
import pt.planet.dto.CustomerResponse;
import pt.planet.dto.ImportErrorResponse;
import pt.planet.dto.ImportResponse;
import pt.planet.dto.ImportResponse.StatusEnum;
import pt.planet.dto.PageCustomerResponse;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    CustomerResponse toResponse(CustomerEntity entity);

    List<CustomerResponse> toResponseList(List<CustomerEntity> entities);

    @Mapping(target = "importId", source = "id")
    @Mapping(target = "status", source = "status")
    ImportResponse toImportResponse(ImportEntity entity);

    default StatusEnum mapStatus(ImportStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case SUCCESS -> ImportResponse.StatusEnum.SUCCESS;
            case PARTIAL_SUCCESS -> ImportResponse.StatusEnum.PARTIAL_SUCCESS;
            case FAILED -> ImportResponse.StatusEnum.FAILED;
        };
    }

    @Mapping(target = "id", source = "id")
    ImportErrorResponse toErrorResponse(ImportErrorEntity entity);

    List<ImportErrorResponse> toErrorResponseList(List<ImportErrorEntity> entities);

    default List<pt.planet.dto.GroupedImportErrorResponse> toGroupedErrorResponseList(List<ImportErrorEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.Map<String, pt.planet.dto.GroupedImportErrorResponse> grouped = new java.util.LinkedHashMap<>();
        for (ImportErrorEntity err : entities) {
            String key = (err.getFilename() != null ? err.getFilename() : "") + ":" + err.getRowNumber();
            pt.planet.dto.GroupedImportErrorResponse group = grouped.computeIfAbsent(key, k -> {
                pt.planet.dto.GroupedImportErrorResponse item = new pt.planet.dto.GroupedImportErrorResponse();
                item.setImportId(err.getImportId());
                item.setFilename(err.getFilename());
                item.setRowNumber(err.getRowNumber());
                item.setRawData(err.getRawData());
                item.setFieldNames(new java.util.ArrayList<>());
                item.setErrorMessages(new java.util.ArrayList<>());
                return item;
            });

            if (group.getRawData() == null && err.getRawData() != null) {
                group.setRawData(err.getRawData());
            }

            if (err.getFieldName() != null && !err.getFieldName().isBlank()) {
                group.getFieldNames().add(err.getFieldName());
            }

            if (err.getErrorMessage() != null && !err.getErrorMessage().isBlank()) {
                group.getErrorMessages().add(err.getErrorMessage());
            }
        }
        return new java.util.ArrayList<>(grouped.values());
    }

    default PageCustomerResponse toPageResponse(Page<CustomerEntity> page) {
        if (page == null) {
            return null;
        }
        PageCustomerResponse response = new PageCustomerResponse();
        response.setContent(toResponseList(page.getContent()));
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setIsFirst(page.isFirst());
        response.setIsLast(page.isLast());
        return response;
    }
}
