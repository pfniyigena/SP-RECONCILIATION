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
import nika.tax.reporter.dto.StampMachineForm;
import nika.tax.reporter.postgres.domain.StampMachine;
import nika.tax.reporter.service.DuplicateValueException;
import nika.tax.reporter.service.StampMachineMapper;
import nika.tax.reporter.service.StampMachineService;

@Controller
@RequestMapping("/stamp-machines")
@RequiredArgsConstructor
public class StampMachineController {

    private final StampMachineService stampMachineService;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                        @PageableDefault(size = 25, sort = "serialNumber") Pageable pageable,
                        Model model) {
        Page<StampMachine> stampMachines = stampMachineService.search(q, pageable);
        model.addAttribute("stampMachines", stampMachines);
        model.addAttribute("q", q);
        return "stamp-machines/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        addFormToModel(model, StampMachineForm.builder().build());
        model.addAttribute("isEdit", false);
        return "stamp-machines/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") StampMachineForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("isEdit", false);
            return "stamp-machines/form";
        }
        try {
            StampMachine saved = stampMachineService.create(form);
            redirectAttributes.addFlashAttribute("flashMessage", "Stamp machine created.");
            return "redirect:/stamp-machines/" + saved.getId() + "/edit";
        } catch (DuplicateValueException e) {
            bindingResult.addError(new FieldError("form", e.getField() != null ? e.getField() : "serialNumber", e.getMessage()));
            model.addAttribute("isEdit", false);
            return "stamp-machines/form";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        StampMachine entity = stampMachineService.getOrThrow(id);
        addFormToModel(model, StampMachineMapper.toForm(entity));
        model.addAttribute("isEdit", true);
        return "stamp-machines/form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") StampMachineForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "stamp-machines/form";
        }
        try {
            stampMachineService.update(id, form);
            redirectAttributes.addFlashAttribute("flashMessage", "Stamp machine updated.");
            return "redirect:/stamp-machines/" + id + "/edit";
        } catch (DuplicateValueException e) {
            bindingResult.addError(new FieldError("form", e.getField() != null ? e.getField() : "serialNumber", e.getMessage()));
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "stamp-machines/form";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        boolean deleted = stampMachineService.delete(id);
        redirectAttributes.addFlashAttribute("flashMessage", deleted
                ? "Stamp machine deleted."
                : "Can't delete this stamp machine — one or more terminal machines still reference it.");
        return "redirect:/stamp-machines";
    }

    private void addFormToModel(Model model, StampMachineForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }
}
