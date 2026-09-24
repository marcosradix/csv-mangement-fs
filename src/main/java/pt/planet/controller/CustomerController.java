package pt.planet.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import pt.planet.api.CustomersApi;
import pt.planet.domain.CustomerEntity;
import pt.planet.dto.PageCustomerResponse;
import pt.planet.mapper.CustomerMapper;
import pt.planet.repository.CustomerRepository;

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class CustomerController implements CustomersApi {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "name", "email", "age", "country", "phone", "createdAt", "updatedAt");

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    @Override
    public ResponseEntity<PageCustomerResponse> listCustomers(
            Integer page,
            Integer size,
            String sortBy,
            String direction) {

        Sort.Direction sortDirection = "DESC".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String sortField = (sortBy != null && ALLOWED_SORT_FIELDS.contains(sortBy)) ? sortBy : "id";

        Pageable pageable = PageRequest.of(page != null ? page : 0, size != null ? size : 20,
                Sort.by(sortDirection, sortField));
        Page<CustomerEntity> customerPage = customerRepository.findAll(pageable);

        return ResponseEntity.ok(customerMapper.toPageResponse(customerPage));
    }
}
