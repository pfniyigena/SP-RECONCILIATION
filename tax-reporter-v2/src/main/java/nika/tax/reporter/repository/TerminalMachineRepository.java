package nika.tax.reporter.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import nika.tax.reporter.postgres.domain.TerminalMachine;

public interface TerminalMachineRepository
        extends JpaRepository<TerminalMachine, UUID>, JpaSpecificationExecutor<TerminalMachine> {
}
