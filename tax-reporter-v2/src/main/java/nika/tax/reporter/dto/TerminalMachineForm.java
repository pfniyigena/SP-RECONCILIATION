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
public class TerminalMachineForm {

    private UUID id;

    @NotBlank(message = "POS name is required")
    private String posName;

    private Integer posId;
    private Integer posNumber;
    private String addressName;
    private String addressDescription;
    private String sdcId;
    private String location;

    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    /** Selected via a dropdown; the entity's actual StampMachine is resolved in the service. */
    private UUID stampMachineId;
}
