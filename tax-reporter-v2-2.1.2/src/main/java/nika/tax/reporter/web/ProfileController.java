package nika.tax.reporter.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.ChangePasswordForm;
import nika.tax.reporter.service.AppUserService;
import nika.tax.reporter.service.ChangePasswordException;

/**
 * Self-service account page — every authenticated user regardless of role
 * (Admin, Analyst, Customer-Scoped) can reach this and change their own
 * password. Deliberately separate from UserController, which is Admin-only
 * and can edit *any* user's password; this controller only ever acts on the
 * currently authenticated principal, never a caller-supplied user id.
 */
@Controller
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final AppUserService appUserService;

    @GetMapping
    public String view(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        addFormToModel(model, ChangePasswordForm.builder().build());
        return "profile";
    }

    @PostMapping("/password")
    public String changePassword(@Valid @ModelAttribute("form") ChangePasswordForm form,
                                  BindingResult bindingResult,
                                  Authentication authentication,
                                  Model model,
                                  RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("username", authentication.getName());
            return "profile";
        }
        try {
            appUserService.changeOwnPassword(authentication.getName(), form);
            redirectAttributes.addFlashAttribute("flashMessage", "Password updated.");
            return "redirect:/profile";
        } catch (ChangePasswordException e) {
            bindingResult.addError(new FieldError("form", e.getField(), e.getMessage()));
        }
        model.addAttribute("username", authentication.getName());
        return "profile";
    }

    private void addFormToModel(Model model, ChangePasswordForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }
}
