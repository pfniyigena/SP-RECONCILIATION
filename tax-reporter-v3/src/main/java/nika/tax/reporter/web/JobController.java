package nika.tax.reporter.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.service.CustomerDepositMatchingService;

/**
 * Operational page for background jobs — currently just deposit matching, but built as a
 * list rather than a single hardcoded page so a second job (e.g. porting the real app's
 * matchingCardWithInvoices()) has an obvious place to go later without restructuring this.
 * Admin-only (see SecurityConfig) — running a bulk background job manually is an operational
 * action, not something every role should be able to trigger.
 */
@Controller
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final CustomerDepositMatchingService customerDepositMatchingService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("depositMatchingRunning", customerDepositMatchingService.isRunning());
        return "jobs";
    }

    @PostMapping("/deposit-matching/run")
    public String runDepositMatching(RedirectAttributes redirectAttributes) {
        String message = customerDepositMatchingService.matchAll()
                .map(result -> result.transactionsCompleted() == 0
                        ? "No unmatched transactions found to match."
                        : result.transactionsCompleted() + " transaction(s) matched across "
                                + result.customersProcessed() + " customer(s).")
                .orElse("Matching is already running — try again shortly.");
        redirectAttributes.addFlashAttribute("flashMessage", message);
        return "redirect:/jobs";
    }
}
