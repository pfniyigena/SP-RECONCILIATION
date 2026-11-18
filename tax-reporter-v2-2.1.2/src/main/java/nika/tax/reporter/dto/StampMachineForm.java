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
public class StampMachineForm {

    private UUID id;

    @NotBlank(message = "Serial number is required")
    private String serialNumber;

    private String model;

    private String sdcId;

    @Builder.Default
    private Boolean enabled = Boolean.TRUE;
}
