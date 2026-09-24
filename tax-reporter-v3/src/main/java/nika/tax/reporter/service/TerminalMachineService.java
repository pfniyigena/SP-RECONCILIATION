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
import nika.tax.reporter.dto.TerminalMachineForm;
import nika.tax.reporter.postgres.domain.StampMachine;
import nika.tax.reporter.postgres.domain.TerminalMachine;
import nika.tax.reporter.repository.StampMachineRepository;
import nika.tax.reporter.repository.TerminalMachineRepository;

@Service
@RequiredArgsConstructor
public class TerminalMachineService {

    private final TerminalMachineRepository repository;
    private final StampMachineRepository stampMachineRepository;

    public Page<TerminalMachine> search(String q, Pageable pageable) {
        Specification<TerminalMachine> spec = Specification.allOf();
        if (StringUtils.hasText(q)) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("posName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("sdcId"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("location"), "")), like)));
        }
        return repository.findAll(spec, pageable);
    }

    public TerminalMachine getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No terminal machine found with id " + id));
    }

    public TerminalMachine create(TerminalMachineForm form) {
        TerminalMachine entity = new TerminalMachine();
        TerminalMachineMapper.applyToEntity(form, entity, resolveStampMachine(form.getStampMachineId()));
        return repository.save(entity);
    }

    public TerminalMachine update(UUID id, TerminalMachineForm form) {
        TerminalMachine entity = getOrThrow(id);
        TerminalMachineMapper.applyToEntity(form, entity, resolveStampMachine(form.getStampMachineId()));
        return repository.save(entity);
    }

    /** @return false if delete failed because transactions still reference this machine. */
    public boolean delete(UUID id) {
        try {
            repository.deleteById(id);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private StampMachine resolveStampMachine(UUID stampMachineId) {
        return stampMachineId != null ? stampMachineRepository.findById(stampMachineId).orElse(null) : null;
    }
}
