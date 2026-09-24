package nika.tax.reporter.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.CustomerForm;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.repository.CustomerRepository;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repository;

    public Page<Customer> search(String q, Pageable pageable) {
        Specification<Customer> spec = Specification.allOf();
        if (StringUtils.hasText(q)) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("clientName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("clientTin"), "")), like)));
        }
        return repository.findAll(spec, pageable);
    }

    public Customer getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No customer found with id " + id));
    }

    public Customer create(CustomerForm form) {
        Customer entity = new Customer();
        CustomerMapper.applyToEntity(form, entity);
        return saveOrThrowDuplicate(entity);
    }

    public Customer update(UUID id, CustomerForm form) {
        Customer entity = getOrThrow(id);
        CustomerMapper.applyToEntity(form, entity);
        return saveOrThrowDuplicate(entity);
    }

    /** @return false if delete failed because transactions still reference this customer. */
    public boolean delete(UUID id) {
        try {
            repository.deleteById(id);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private Customer saveOrThrowDuplicate(Customer entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateValueException("A customer named \"" + entity.getClientName() + "\" already exists.");
        }
    }
}
