package nika.tax.reporter.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import nika.tax.reporter.postgres.domain.StampMachine;

public interface StampMachineRepository extends JpaRepository<StampMachine, UUID>, JpaSpecificationExecutor<StampMachine> {

    /** Returns null if no match. sdcId is unique on this entity, so this is guaranteed 0 or 1 results. */
    StampMachine getBySdcId(String sdcId);

    /** SDC IDs for every enabled stamp machine that actually has one set — the driving list
     * for PostgresJob's per-SDC parallel matching run. sdcId is optional on this entity
     * (added later, see the entity's own javadoc), so this excludes nulls rather than handing
     * the job a list it would have to null-check itself. */
    @Query("select s.sdcId from StampMachine s where s.enabled = true and s.sdcId is not null")
    List<String> findAllEnabledSdcIds();
}
