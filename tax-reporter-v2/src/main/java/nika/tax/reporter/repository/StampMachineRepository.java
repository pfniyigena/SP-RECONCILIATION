package nika.tax.reporter.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import nika.tax.reporter.postgres.domain.StampMachine;

public interface StampMachineRepository extends JpaRepository<StampMachine, UUID>, JpaSpecificationExecutor<StampMachine> {

    /** Returns null if no match. sdcId is unique on this entity, so this is guaranteed 0 or 1 results. */
    StampMachine getBySdcId(String sdcId);
}
