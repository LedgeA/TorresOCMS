package com.mmtorresoptical.OpticalClinicManagementSystem.dto.refund;


import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RefundItemDTO {

    @NotNull
    private UUID transactionItemId;

    @NotNull
    @Positive
    private Integer refundQuantity;

    private String refundReason; // optional but recommended

    private List<RefundBatchAllocationDTO> batchAllocations; // optional: explicit batch targets for stock restoration
}