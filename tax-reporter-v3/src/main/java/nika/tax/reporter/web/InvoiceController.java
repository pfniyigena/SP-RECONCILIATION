package nika.tax.reporter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.TaxReporterInvoiceForm;
import nika.tax.reporter.postgres.domain.TaxReporterInvoice;
import nika.tax.reporter.service.DuplicateValueException;
import nika.tax.reporter.service.TaxReporterInvoiceFilter;
import nika.tax.reporter.service.TaxReporterInvoiceMapper;
import nika.tax.reporter.service.TaxReporterInvoiceService;

@Controller
@RequestMapping("/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final TaxReporterInvoiceService invoiceService;

    @GetMapping
    public String list(
            @RequestParam Optional<String> q,
            @RequestParam Optional<String> status,
            @RequestParam Optional<LocalDate> dateFrom,
            @RequestParam Optional<LocalDate> dateTo,
            @RequestParam Optional<BigDecimal> minAmount,
            @RequestParam Optional<BigDecimal> maxAmount,
            @RequestParam Optional<String> plateNumber,
            @PageableDefault(size = 50, sort = "stampDate", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {

        TaxReporterInvoiceFilter filter = buildFilter(q, status, dateFrom, dateTo, minAmount, maxAmount, plateNumber);

        Page<TaxReporterInvoice> invoices = invoiceService.search(filter, pageable);

        boolean hasActiveFilters = filter.getQ() != null && !filter.getQ().isBlank()
                || filter.getStatus() != null && !filter.getStatus().isBlank()
                || filter.getDateFrom() != null
                || filter.getDateTo() != null
                || filter.getMinAmount() != null
                || filter.getMaxAmount() != null
                || filter.getPlateNumber() != null && !filter.getPlateNumber().isBlank();

        String sortProperty = pageable.getSort().stream()
                .findFirst().map(Sort.Order::getProperty).orElse("stampDate");
        String sortDirection = pageable.getSort().stream()
                .findFirst().map(o -> o.getDirection().name().toLowerCase()).orElse("desc");

        model.addAttribute("invoices", invoices);
        model.addAttribute("pageSubtotal", invoiceService.pageSubtotal(invoices));
        model.addAttribute("filter", filter);
        model.addAttribute("hasActiveFilters", hasActiveFilters);
        model.addAttribute("sortProperty", sortProperty);
        model.addAttribute("sortDirection", sortDirection);

        return "invoices";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        model.addAttribute("inv", invoiceService.getOrThrow(id));
        return "invoice-view";
    }

    /** Mirrors CardTransactionController.process() — the same one-click "mark as processed"
     * action, adapted for this entity's processed + failureReason fields (no separate success
     * flag exists here, see TaxReporterInvoiceService.markProcessed). No customer-scoping
     * check needed the way CardTransaction's has, since Invoices are Admin/Analyst-only at the
     * SecurityConfig level with no ROLE_CUSTOMER_SCOPED access at all. */
    @PostMapping("/{id}/process")
    public String process(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        invoiceService.markProcessed(id);
        redirectAttributes.addFlashAttribute("flashMessage", "Invoice marked as processed.");
        return "redirect:/invoices/" + id;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        TaxReporterInvoice entity = invoiceService.getOrThrow(id);
        addFormToModel(model, TaxReporterInvoiceMapper.toForm(entity));
        model.addAttribute("isEdit", true);
        return "invoice-form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id,
                          @Valid @ModelAttribute("form") TaxReporterInvoiceForm form,
                          BindingResult bindingResult,
                          Model model,
                          RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "invoice-form";
        }
        try {
            invoiceService.update(id, form);
            redirectAttributes.addFlashAttribute("flashMessage", "Invoice updated.");
            return "redirect:/invoices/" + id;
        } catch (DuplicateValueException e) {
            bindingResult.addError(new FieldError("form", "stampData", e.getMessage()));
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "invoice-form";
        }
    }

    private void addFormToModel(Model model, TaxReporterInvoiceForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }

    private TaxReporterInvoiceFilter buildFilter(
            Optional<String> q, Optional<String> status,
            Optional<LocalDate> dateFrom, Optional<LocalDate> dateTo,
            Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount,
            Optional<String> plateNumber) {
        return TaxReporterInvoiceFilter.builder()
                .q(q.orElse(null))
                .status(status.orElse(null))
                .dateFrom(dateFrom.orElse(null))
                .dateTo(dateTo.orElse(null))
                .minAmount(minAmount.orElse(null))
                .maxAmount(maxAmount.orElse(null))
                .plateNumber(plateNumber.orElse(null))
                .build();
    }
}
