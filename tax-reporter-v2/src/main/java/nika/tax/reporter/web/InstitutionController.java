package nika.tax.reporter.web;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.InstitutionForm;
import nika.tax.reporter.postgres.domain.Institution;
import nika.tax.reporter.service.InstitutionMapper;
import nika.tax.reporter.service.InstitutionService;

@Controller
@RequestMapping("/institutions")
@RequiredArgsConstructor
public class InstitutionController {

    private final InstitutionService institutionService;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                        @PageableDefault(size = 25, sort = "name") Pageable pageable,
                        Model model) {
        Page<Institution> institutions = institutionService.search(q, pageable);
        model.addAttribute("institutions", institutions);
        model.addAttribute("q", q);
        return "institutions/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        addFormToModel(model, InstitutionForm.builder().build());
        model.addAttribute("isEdit", false);
        return "institutions/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") InstitutionForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("isEdit", false);
            return "institutions/form";
        }
        Institution saved = institutionService.create(form);
        redirectAttributes.addFlashAttribute("flashMessage", "Institution created.");
        return "redirect:/institutions/" + saved.getId() + "/edit";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        Institution entity = institutionService.getOrThrow(id);
        addFormToModel(model, InstitutionMapper.toForm(entity));
        model.addAttribute("isEdit", true);
        return "institutions/form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") InstitutionForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "institutions/form";
        }
        institutionService.update(id, form);
        redirectAttributes.addFlashAttribute("flashMessage", "Institution updated.");
        return "redirect:/institutions/" + id + "/edit";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        boolean deleted = institutionService.delete(id);
        redirectAttributes.addFlashAttribute("flashMessage", deleted
                ? "Institution deleted."
                : "Can't delete this institution — one or more records still reference it.");
        return "redirect:/institutions";
    }

    private void addFormToModel(Model model, InstitutionForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }
}
