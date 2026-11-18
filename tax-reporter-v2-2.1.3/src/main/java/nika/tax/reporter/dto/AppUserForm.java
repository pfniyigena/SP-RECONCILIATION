package nika.tax.reporter.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUserForm {

    private UUID id;

    @NotBlank(message = "Username is required")
    private String username;

    private String fullName;

    @NotBlank(message = "Role is required")
    private String role;

    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    /**
     * Deliberately NOT validated with @NotBlank/@Size here: required on create,
     * optional on edit (blank = keep the existing password). That "required
     * depends on create vs edit" logic can't be expressed with a single bean
     * validation annotation, so it's checked manually in AppUserService instead.
     */
    private String password;

    /**
     * Only meaningful when role is ROLE_CUSTOMER_SCOPED — ignored entirely for
     * every other role. Kept as a plain list here regardless of role so the
     * form doesn't lose whatever was selected if validation fails and the
     * page re-renders.
     */
    @Builder.Default
    private List<UUID> accessibleCustomerIds = List.of();
}

