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
import nika.tax.reporter.dto.InstitutionForm;
import nika.tax.reporter.postgres.domain.Institution;
import nika.tax.reporter.repository.InstitutionRepository;

@Service
@RequiredArgsConstructor
public class InstitutionService {

    private final InstitutionRepository repository;

    public Page<Institution> search(String q, Pageable pageable) {
        Specification<Institution> spec = Specification.allOf();
        if (StringUtils.hasText(q)) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("name"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("tinNumber"), "")), like)));
        }
        return repository.findAll(spec, pageable);
    }

    public Institution getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No institution found with id " + id));
    }

    public Institution update(UUID id, InstitutionForm form) {
        Institution entity = getOrThrow(id);
        InstitutionMapper.applyToEntity(form, entity);
        return repository.saveAndFlush(entity);
    }

}
