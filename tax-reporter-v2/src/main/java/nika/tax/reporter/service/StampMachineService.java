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
import nika.tax.reporter.dto.StampMachineForm;
import nika.tax.reporter.postgres.domain.StampMachine;
import nika.tax.reporter.repository.StampMachineRepository;

@Service
@RequiredArgsConstructor
public class StampMachineService {

    private final StampMachineRepository repository;

    public Page<StampMachine> search(String q, Pageable pageable) {
        Specification<StampMachine> spec = Specification.allOf();
        if (StringUtils.hasText(q)) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("serialNumber"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("model"), "")), like)));
        }
        return repository.findAll(spec, pageable);
    }

    public StampMachine getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No stamp machine found with id " + id));
    }

    public StampMachine create(StampMachineForm form) {
        StampMachine entity = new StampMachine();
        StampMachineMapper.applyToEntity(form, entity);
        return saveOrThrowDuplicate(entity);
    }

    public StampMachine update(UUID id, StampMachineForm form) {
        StampMachine entity = getOrThrow(id);
        StampMachineMapper.applyToEntity(form, entity);
        return saveOrThrowDuplicate(entity);
    }

    /** @return false if delete failed because a terminal machine still references this stamp machine. */
    public boolean delete(UUID id) {
        try {
            repository.deleteById(id);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * Two unique constraints exist now (serialNumber, sdcId) — checks which one the
     * underlying SQL error actually names rather than always blaming serialNumber, which
     * would otherwise give a misleading message (and, via the controller's error handling,
     * highlight the wrong form field) if an sdcId conflict was what actually happened.
     */
    private StampMachine saveOrThrowDuplicate(StampMachine entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : null;
            if (detail != null && detail.toLowerCase().contains("sdc_id")) {
                throw new DuplicateValueException("sdcId",
                        "A stamp machine with SDC ID \"" + entity.getSdcId() + "\" already exists.");
            }
            throw new DuplicateValueException("serialNumber",
                    "A stamp machine with serial number \"" + entity.getSerialNumber() + "\" already exists.");
        }
    }
}
