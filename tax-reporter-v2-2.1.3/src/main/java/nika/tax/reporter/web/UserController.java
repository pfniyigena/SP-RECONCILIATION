package nika.tax.reporter.web;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
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
import nika.tax.reporter.dto.AppUserForm;
import nika.tax.reporter.postgres.domain.AppUser;
import nika.tax.reporter.repository.CustomerRepository;
import nika.tax.reporter.service.AppUserMapper;
import nika.tax.reporter.service.AppUserService;
import nika.tax.reporter.service.DuplicateValueException;
import nika.tax.reporter.service.InvalidPasswordException;

@Controller
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final AppUserService appUserService;
    private final CustomerRepository customerRepository;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                        @PageableDefault(size = 25, sort = "username") Pageable pageable,
                        Model model) {
        Page<AppUser> users = appUserService.search(q, pageable);
        model.addAttribute("users", users);
        model.addAttribute("q", q);
        return "users/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        addFormToModel(model, AppUserForm.builder().role("ROLE_ANALYST").build());
        model.addAttribute("isEdit", false);
        model.addAttribute("customers", customerRepository.findAll(Sort.by("clientName")));
        return "users/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") AppUserForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("isEdit", false);
            model.addAttribute("customers", customerRepository.findAll(Sort.by("clientName")));
            return "users/form";
        }
        try {
            appUserService.create(form);
            redirectAttributes.addFlashAttribute("flashMessage", "User created.");
            return "redirect:/users";
        } catch (DuplicateValueException e) {
            bindingResult.addError(new FieldError("form", "username", e.getMessage()));
        } catch (InvalidPasswordException e) {
            bindingResult.addError(new FieldError("form", "password", e.getMessage()));
        }
        model.addAttribute("isEdit", false);
        model.addAttribute("customers", customerRepository.findAll(Sort.by("clientName")));
        return "users/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        AppUser entity = appUserService.getOrThrow(id);
        addFormToModel(model, AppUserMapper.toForm(entity));
        model.addAttribute("isEdit", true);
        model.addAttribute("customers", customerRepository.findAll(Sort.by("clientName")));
        return "users/form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") AppUserForm form,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            model.addAttribute("customers", customerRepository.findAll(Sort.by("clientName")));
            return "users/form";
        }
        try {
            appUserService.update(id, form);
            redirectAttributes.addFlashAttribute("flashMessage", "User updated.");
            return "redirect:/users";
        } catch (DuplicateValueException e) {
            bindingResult.addError(new FieldError("form", "username", e.getMessage()));
        } catch (InvalidPasswordException e) {
            bindingResult.addError(new FieldError("form", "password", e.getMessage()));
        }
        form.setId(id);
        model.addAttribute("isEdit", true);
        model.addAttribute("customers", customerRepository.findAll(Sort.by("clientName")));
        return "users/form";
    }

    private void addFormToModel(Model model, AppUserForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }
}

