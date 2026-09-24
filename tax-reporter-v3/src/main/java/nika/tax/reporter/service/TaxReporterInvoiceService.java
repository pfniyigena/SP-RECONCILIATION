package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.dto.InvoiceDto;
import nika.tax.reporter.dto.TaxReporterInvoiceForm;
import nika.tax.reporter.postgres.domain.Institution;
import nika.tax.reporter.postgres.domain.StampMachine;
import nika.tax.reporter.postgres.domain.TaxReporterInvoice;
import nika.tax.reporter.repository.InstitutionRepository;
import nika.tax.reporter.repository.StampMachineRepository;
import nika.tax.reporter.repository.TaxReporterInvoiceRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaxReporterInvoiceService {

    private final TaxReporterInvoiceRepository repository;
    private final InstitutionRepository institutionRepository;
    private final StampMachineRepository stampMachineRepository;

    public Page<TaxReporterInvoice> search(TaxReporterInvoiceFilter filter, Pageable pageable) {
        return repository.findAll(TaxReporterInvoiceSpecifications.build(filter), pageable);
    }

    public TaxReporterInvoice getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No invoice found with id " + id));
    }

    public TaxReporterInvoice create(TaxReporterInvoiceForm form) {
        TaxReporterInvoice entity = new TaxReporterInvoice();
        TaxReporterInvoiceMapper.applyToEntity(form, entity);
        return saveOrThrowDuplicate(entity);
    }

    public TaxReporterInvoice update(UUID id, TaxReporterInvoiceForm form) {
        TaxReporterInvoice entity = getOrThrow(id);
        TaxReporterInvoiceMapper.applyToEntity(form, entity);
        return saveOrThrowDuplicate(entity);
    }

    /**
     * Analogous to CardTransactionService.markProcessed — this entity has processed +
     * failureReason instead of processed + success, so the direct equivalent of "mark
     * successfully processed" is setting processed=true AND clearing any stale failureReason,
     * rather than setting a second success flag that doesn't exist here.
     */
    public TaxReporterInvoice markProcessed(UUID id) {
        TaxReporterInvoice entity = getOrThrow(id);
        entity.setProcessed(true);
        entity.setFailureReason(null);
        return repository.save(entity);
    }

    /**
     * Sums totalAmount for whatever page of results is currently displayed —
     * a page-level subtotal only, same caveat as CardTransactionService.
     */
    public BigDecimal pageSubtotal(Page<TaxReporterInvoice> page) {
        return page.getContent().stream()
                .map(TaxReporterInvoice::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private TaxReporterInvoice saveOrThrowDuplicate(TaxReporterInvoice entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateValueException(
                    "An invoice with this stamp data already exists (stamp_data is unique).");
        }
    }

    // ---- Invoice ingestion (POST /api/v1/invoices — see api.InvoiceApiV1Controller) ----
    //
    // This path deliberately swallows exceptions and returns null rather than propagating —
    // matches the real app's behavior exactly, not something introduced here. Worth knowing
    // if you're calling this from elsewhere: a null return means SOMETHING went wrong, but the
    // caller can't distinguish "duplicate stamp_data" from "DB unreachable" from anything else
    // without checking the logs. InvoiceApiV1Controller returns HTTP 200 either way, with
    // SUCCESS/FAILED indicated only in the response body's "status" field — not by the HTTP
    // status code — so a caller checking only the status code can't tell these apart at all.

    public TaxReporterInvoice createInvoice(InvoiceDto invoiceDto) {
        try {
            if (invoiceDto == null)
                return null;
            validStampMachine(invoiceDto.getRegisteredName(), invoiceDto.getRegisteredTin(), invoiceDto.getSdcId());
            TaxReporterInvoice exist = repository.getByStampData(invoiceDto.getStampData());
            if (exist == null) {
                TaxReporterInvoice invoice = toInvoice(invoiceDto);
                return repository.save(invoice);
            } else {
                return exist;
            }
        } catch (Exception e) {
            log.error("createInvoice:{}", e.getMessage(), e);
            return null;
        }
    }

    private void validStampMachine(String name, String tinNumber, String sdcId) {
        try {
            Institution institution = validInstitution(name, tinNumber);
            if (institution != null && sdcId != null && !sdcId.isEmpty() && !sdcId.isEmpty()) {
                if (stampMachineRepository.getBySdcId(sdcId) == null) {
                    stampMachineRepository.save(
                            StampMachine.builder().institution(institution).sdcId(sdcId).enabled(Boolean.TRUE).build());
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    private Institution validInstitution(String name, String tinNumber) {
        try {
            Institution institution = null;
            if (tinNumber != null && !tinNumber.isEmpty() && !tinNumber.isBlank()) {
                institution = institutionRepository.getByTinNumber(tinNumber);
                if (institution == null) {
                    institution = institutionRepository
                            .save(Institution.builder().name(name).tinNumber(tinNumber).enabled(Boolean.TRUE).build());
                }
            }
            return institution;
        } catch (Exception e) {
            log.error(e.getMessage());
            return null;
        }
    }

    private TaxReporterInvoice toInvoice(InvoiceDto invoiceDto) {
        TaxReporterInvoice invoice = new TaxReporterInvoice();
        invoice.setRegisteredName(invoiceDto.getRegisteredName());
        invoice.setRegisteredTin(invoiceDto.getRegisteredTin());
        invoice.setClientName(invoiceDto.getClientName());
        invoice.setClientTin(invoiceDto.getClientTin());
        invoice.setReceiptNumber(invoiceDto.getReceiptNumber());
        invoice.setClientPhone(invoiceDto.getClientPhone());
        invoice.setSdcId(invoiceDto.getSdcId());
        invoice.setStampDate(invoiceDto.getStampDate());
        invoice.setStampData(invoiceDto.getStampData());
        invoice.setTotalTaxAmount(invoiceDto.getTotalTaxAmount());
        invoice.setTotalAmount(invoiceDto.getTotalAmount());
        invoice.setPaidAmount(invoiceDto.getPaidAmount());
        invoice.setTransactionType(invoiceDto.getTransactionType());
        invoice.setPaymentMode(invoiceDto.getPaymentMode());
        invoice.setPlateNumber(invoiceDto.getPlateNumber());
        invoice.setErpCode(invoiceDto.getErpCode());
        return invoice;
    }
}
