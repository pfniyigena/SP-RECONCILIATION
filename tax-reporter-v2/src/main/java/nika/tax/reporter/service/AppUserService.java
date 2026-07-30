package nika.tax.reporter.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.AppUserForm;
import nika.tax.reporter.dto.ChangePasswordForm;
import nika.tax.reporter.postgres.domain.AppUser;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.repository.AppUserRepository;
import nika.tax.reporter.repository.CustomerRepository;

@Service
@RequiredArgsConstructor
public class AppUserService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository repository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

    public Page<AppUser> search(String q, Pageable pageable) {
        Specification<AppUser> spec = Specification.allOf();
        if (StringUtils.hasText(q)) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("username"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("fullName"), "")), like)));
        }
        return repository.findAll(spec, pageable);
    }

    public AppUser getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with id " + id));
    }

    public AppUser create(AppUserForm form) {
        if (!StringUtils.hasText(form.getPassword())) {
            throw new InvalidPasswordException("A password is required when creating a user.");
        }
        if (form.getPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidPasswordException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }

        AppUser entity = new AppUser();
        AppUserMapper.applyToEntity(form, entity);
        entity.setPassword(passwordEncoder.encode(form.getPassword()));
        entity.setAccessibleCustomers(resolveCustomers(form.getAccessibleCustomerIds()));
        return saveOrThrowDuplicate(entity);
    }

    /** Blank password on the form means "leave it unchanged". */
    public AppUser update(UUID id, AppUserForm form) {
        AppUser entity = getOrThrow(id);
        AppUserMapper.applyToEntity(form, entity);
        entity.setAccessibleCustomers(resolveCustomers(form.getAccessibleCustomerIds()));

        if (StringUtils.hasText(form.getPassword())) {
            if (form.getPassword().length() < MIN_PASSWORD_LENGTH) {
                throw new InvalidPasswordException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            }
            entity.setPassword(passwordEncoder.encode(form.getPassword()));
        }

        return saveOrThrowDuplicate(entity);
    }

    /**
     * @return a message describing why delete was refused, or null if it succeeded.
     * Refuses to delete the account currently signed in (avoids locking yourself out)
     * and refuses to delete the last remaining user account (avoids locking everyone out).
     */
    public String delete(UUID id, String currentUsername) {
        AppUser target = getOrThrow(id);

        if (target.getUsername().equals(currentUsername)) {
            return "You can't delete your own account while signed in as it.";
        }
        if (repository.count() <= 1) {
            return "Can't delete the last remaining user account.";
        }

        try {
            repository.deleteById(id);
            return null;
        } catch (DataIntegrityViolationException e) {
            return "Can't delete this user — something still references it.";
        }
    }

    /**
     * Self-service password change — used by /profile, distinct from update() above
     * (which is the Admin-only "edit any user" path from the Users screen). Verifies
     * the caller actually knows their current password before allowing a change; this
     * method never trusts a caller-supplied username the way update()/getOrThrow(id)
     * would, it's always resolved from the authenticated principal by the controller.
     */
    public void changeOwnPassword(String username, ChangePasswordForm form) {
        AppUser entity = repository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with username " + username));

        if (!passwordEncoder.matches(form.getCurrentPassword(), entity.getPassword())) {
            throw new ChangePasswordException("currentPassword", "Current password is incorrect.");
        }
        if (form.getNewPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new ChangePasswordException("newPassword", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (!form.getNewPassword().equals(form.getConfirmPassword())) {
            throw new ChangePasswordException("confirmPassword", "Passwords do not match.");
        }
        if (passwordEncoder.matches(form.getNewPassword(), entity.getPassword())) {
            throw new ChangePasswordException("newPassword", "New password must be different from your current password.");
        }

        entity.setPassword(passwordEncoder.encode(form.getNewPassword()));
        repository.saveAndFlush(entity);
    }

    private Set<Customer> resolveCustomers(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(customerRepository.findAllById(ids));
    }

    private AppUser saveOrThrowDuplicate(AppUser entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateValueException("A user named \"" + entity.getUsername() + "\" already exists.");
        }
    }
}

