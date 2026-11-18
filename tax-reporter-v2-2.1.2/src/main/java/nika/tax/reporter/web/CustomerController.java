package nika.tax.reporter.web;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import nika.tax.reporter.dto.CustomerForm;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.service.CustomerMapper;
import nika.tax.reporter.service.CustomerService;
import nika.tax.reporter.service.DuplicateValueException;

@Controller
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                        @PageableDefault(size = 25, sort = "clientName") Pageable pageable,
                        Model model) {
        Page<Customer> customers = customerService.search(q, pageable);
        model.addAttribute("customers", customers);
        model.addAttribute("q", q);
        return "customers/list";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        Customer entity = customerService.getOrThrow(id);
        addFormToModel(model, CustomerMapper.toForm(entity));
        model.addAttribute("isEdit", true);
        return "customers/form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") CustomerForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "customers/form";
        }
        try {
            customerService.update(id, form);
            redirectAttributes.addFlashAttribute("flashMessage", "Customer updated.");
            return "redirect:/customers/" + id + "/edit";
        } catch (DuplicateValueException e) {
            bindingResult.addError(new FieldError("form", "clientName", e.getMessage()));
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "customers/form";
        }
    }

    private void addFormToModel(Model model, CustomerForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }
}
