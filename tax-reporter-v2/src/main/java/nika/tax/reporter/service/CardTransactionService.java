package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.CardTransactionForm;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.repository.CardTransactionRepository;

@Service
@RequiredArgsConstructor
public class CardTransactionService {

    private final CardTransactionRepository repository;

    public Page<CardTransaction> search(CardTransactionFilter filter, Pageable pageable) {
        return repository.findAll(CardTransactionSpecifications.build(filter), pageable);
    }

    public CardTransaction getOrThrow(UUID id) {
        return repository.findWithAllocationsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found"));
    }

    public CardTransaction create(CardTransactionForm form) {
        CardTransaction entity = new CardTransaction();
        if (form.getTransactionGuid() == null || form.getTransactionGuid().isBlank()) {
            form.setTransactionGuid(UUID.randomUUID().toString());
        }
        CardTransactionMapper.applyToEntity(form, entity);
        return repository.save(entity);
    }

    public CardTransaction update(UUID id, CardTransactionForm form) {
        CardTransaction entity = getOrThrow(id);
        CardTransactionMapper.applyToEntity(form, entity);
        return repository.save(entity);
    }

    /**
     * Manual one-click "process this transaction" action from the detail view —
     * a shortcut for what's already possible via the edit form's Processed/Success
     * checkboxes, not a new capability. Marks the transaction both processed and
     * successful: a human confirming "this is fine" via this button is a distinct
     * action from the automated matching engine, which can also mark a transaction
     * processed-but-failed; this manual path only ever represents a successful
     * outcome, since a "manually mark this as failed" action isn't what this
     * button is for — that's still an edit-form change, not a one-click action.
     */
    public CardTransaction markProcessed(UUID id) {
        CardTransaction entity = getOrThrow(id);
        entity.setProcessed(true);
        entity.setSuccess(true);
        return repository.save(entity);
    }

    /**
     * Sums totalAmount for whatever page of results is currently displayed.
     * NOTE: this is a page-level subtotal only (cheap, no extra query) — it is
     * NOT a grand total across all matching rows, which would need a separate
     * aggregate query (see CardTransactionRepository if you want to add one
     * with @Query("select sum(t.totalAmount) from CardTransaction t where ...")).
     */
    public BigDecimal pageSubtotal(Page<CardTransaction> page) {
        return page.getContent().stream()
                .map(CardTransaction::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

