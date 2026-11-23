package com.mmtorresoptical.OpticalClinicManagementSystem.dto.refund;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RefundTransactionRequestDTO {

    @NotEmpty
    private List<@NotNull @Valid RefundItemDTO> items;

    private String refundMethod; // CASH, GCASH

    private String gcashNumber;

    private String referenceNumber;

}