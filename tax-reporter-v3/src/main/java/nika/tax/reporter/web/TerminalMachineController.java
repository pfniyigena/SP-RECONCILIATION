package nika.tax.reporter.web;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import nika.tax.reporter.dto.TerminalMachineForm;
import nika.tax.reporter.postgres.domain.TerminalMachine;
import nika.tax.reporter.repository.StampMachineRepository;
import nika.tax.reporter.service.TerminalMachineMapper;
import nika.tax.reporter.service.TerminalMachineService;

@Controller
@RequestMapping("/machines")
@RequiredArgsConstructor
public class TerminalMachineController {

    private final TerminalMachineService terminalMachineService;
    private final StampMachineRepository stampMachineRepository;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                        @PageableDefault(size = 25, sort = "posName") Pageable pageable,
                        Model model) {
        Page<TerminalMachine> machines = terminalMachineService.search(q, pageable);
        model.addAttribute("machines", machines);
        model.addAttribute("q", q);
        return "machines/list";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        TerminalMachine entity = terminalMachineService.getOrThrow(id);
        addFormToModel(model, TerminalMachineMapper.toForm(entity));
        model.addAttribute("isEdit", true);
        model.addAttribute("stampMachines", stampMachineRepository.findAll(Sort.by("serialNumber")));
        return "machines/form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") TerminalMachineForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            model.addAttribute("stampMachines", stampMachineRepository.findAll(Sort.by("serialNumber")));
            return "machines/form";
        }
        terminalMachineService.update(id, form);
        redirectAttributes.addFlashAttribute("flashMessage", "Terminal machine updated.");
        return "redirect:/machines/" + id + "/edit";
    }

    private void addFormToModel(Model model, TerminalMachineForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }
}
