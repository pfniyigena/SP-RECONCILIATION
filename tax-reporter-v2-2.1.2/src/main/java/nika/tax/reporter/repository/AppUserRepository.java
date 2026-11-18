package nika.tax.reporter.repository;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import nika.tax.reporter.postgres.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID>, JpaSpecificationExecutor<AppUser> {
    /** Loads accessibleCustomers eagerly (open-in-view=false, so lazy access after the repository call fails). */
    @EntityGraph(attributePaths = "accessibleCustomers")
    Optional<AppUser> findWithCustomersById(UUID id);

    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
}
