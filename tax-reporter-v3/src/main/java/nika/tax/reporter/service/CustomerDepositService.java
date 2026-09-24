package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.CustomerDepositForm;
import nika.tax.reporter.postgres.domain.CustomerDeposit;
import nika.tax.reporter.postgres.domain.DepositCardTransaction;
import nika.tax.reporter.repository.CustomerDepositRepository;
import nika.tax.reporter.repository.DepositCardTransactionRepository;

@Service
@RequiredArgsConstructor
public class CustomerDepositService {

    private final CustomerDepositRepository repository;
    private final DepositCardTransactionRepository depositCardTransactionRepository;
    private final StampLookupService stampLookupService;

    public Page<CustomerDeposit> search(CustomerDepositFilter filter, Pageable pageable) {
        return repository.findAll(CustomerDepositSpecifications.build(filter), pageable);
    }

    public CustomerDeposit getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No customer deposit found with id " + id));
    }

    public CustomerDeposit create(CustomerDepositForm form) {
        CustomerDeposit entity = new CustomerDeposit();
        if (form.getTransactionGuid() == null || form.getTransactionGuid().isBlank()) {
            form.setTransactionGuid(UUID.randomUUID().toString());
        }
        CustomerDepositMapper.applyToEntity(form, entity);
        // @Builder.Default field initializers (currentBalance = BigDecimal.ZERO on the entity)
        // only apply when constructed via .builder().build() — NOT via `new CustomerDeposit()`
        // as used here, a well-known Lombok gotcha. Without this, currentBalance would be left
        // null, and CustomerDepositRepository.findAvailableDeposits' `currentBalance > minBalance`
        // comparison would silently exclude every newly-created deposit from matching forever
        // (NULL is never greater than anything in SQL) with no error anywhere to explain why.
        // A freshly-created deposit hasn't had anything allocated against it yet, so its full
        // balance starts out equal to its total amount.
        entity.setCurrentBalance(entity.getTotalAmount() != null ? entity.getTotalAmount() : BigDecimal.ZERO);
        return repository.save(entity);
    }

    public CustomerDeposit update(UUID id, CustomerDepositForm form) {
        CustomerDeposit entity = getOrThrow(id);
        CustomerDepositMapper.applyToEntity(form, entity);
        return repository.save(entity);
    }

    /**
     * Looks up this deposit's sapReference against the external SQL Server Stamp table and,
     * if a match is found, applies its stamp_data to this deposit and saves.
     *
     * @return a human-readable outcome message for the flash banner — deliberately a String,
     *         not a boolean, so the three distinct outcomes (updated / no sapReference set /
     *         no matching row found) each get a message that actually explains what happened,
     *         rather than the UI having to guess from a true/false result.
     * @throws StampLookupException if the SQL Server connection/query itself fails — left to
     *         propagate uncaught so the controller can distinguish "couldn't check" (500-ish,
     *         worth surfacing loudly) from the two normal no-op outcomes below.
     */
    public String fetchStampDataFromExternalSource(UUID id) {
        CustomerDeposit entity = getOrThrow(id);

        if (entity.getSapReference() == null || entity.getSapReference().isBlank()) {
            return "This deposit has no SAP reference set — nothing to look up.";
        }

        Optional<String> stampData = stampLookupService.findStampDataBySapReference(entity.getSapReference());
        if (stampData.isEmpty()) {
            return "No stamp found in the Stamp database for SAP reference \"" + entity.getSapReference() + "\".";
        }

        entity.setStampData(stampData.get());
        repository.save(entity);
        return "Stamp data fetched and applied from SAP reference \"" + entity.getSapReference() + "\".";
    }

    /** Page-level subtotal only — see the identical note on CardTransactionService.pageSubtotal. */
    public BigDecimal pageSubtotal(Page<CustomerDeposit> page) {
        return page.getContent().stream()
                .map(CustomerDeposit::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- CustomerDepositMatchingService results, surfaced for the detail page ----

    /**
     * This deposit's allocation records — each one names a CardTransaction it partly or fully
     * funded, and how much. Returns the allocation records themselves (not just the transactions)
     * because the allocated amount for a given transaction may be LESS than that transaction's
     * own totalAmount, if it was split across more than one deposit — showing just the
     * transaction's total here would be misleading about how much THIS deposit actually
     * contributed.
     */
    public List<DepositCardTransaction> matchedAllocations(UUID depositId) {
        return depositCardTransactionRepository.findByDeposit_Id(depositId);
    }

    public long matchedTransactionCount(UUID depositId) {
        return depositCardTransactionRepository.countByDeposit_Id(depositId);
    }

    /** Sum of allocatedAmount across every allocation this deposit has funded so far — note this
     * is independent of, and should always agree with, totalAmount - currentBalance; if it
     * doesn't, something wrote to currentBalance without going through
     * CustomerDepositMatchingService (the only intended writer of both together). */
    public BigDecimal allocatedAmount(UUID depositId) {
        return matchedAllocations(depositId).stream()
                .map(DepositCardTransaction::getAllocatedAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The deposit's own live remaining balance — read directly from currentBalance (denormalized,
     * decremented by CustomerDepositMatchingService as it allocates), not recomputed from a SUM
     * over allocations. O(1), not an aggregate query.
     */
    public BigDecimal remainingBalance(CustomerDeposit deposit) {
        return deposit.getCurrentBalance();
    }
}
