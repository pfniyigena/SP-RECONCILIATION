package nika.tax.reporter.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import nika.tax.reporter.postgres.domain.TerminalMachine;

public interface TerminalMachineRepository
        extends JpaRepository<TerminalMachine, UUID>, JpaSpecificationExecutor<TerminalMachine> {

    /** Used by the Oracle ingestion jobs (OracleJdbcCardTransactionJob) to find or create the
     * terminal a transaction belongs to, keyed by the POS number Oracle reports. */
    Optional<TerminalMachine> getByPosNumber(Integer posNumber);
}
