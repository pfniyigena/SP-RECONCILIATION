package nika.tax.reporter.dto;

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
public class CustomerForm {

    private UUID id;

    @NotBlank(message = "Client name is required")
    private String clientName;

    private String clientTin;

    private Integer clientId;

    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    /** Eligibility flag for CustomerDepositMatchingService — see the same field on the
     * Customer entity for what it actually gates. */
    @Builder.Default
    private Boolean allocated = Boolean.FALSE;
}
