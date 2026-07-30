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
public class InstitutionForm {

    private UUID id;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "TIN number is required")
    private String tinNumber;

    @Builder.Default
    private Boolean enabled = Boolean.TRUE;
}
