package com.mmtorresoptical.OpticalClinicManagementSystem.dto.transaction;

import com.mmtorresoptical.OpticalClinicManagementSystem.dto.transactionitem.TransactionItemsRequestDTO;
import com.mmtorresoptical.OpticalClinicManagementSystem.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class TransactionRequestDTO {

    private UUID patientId;

    private UUID prescriptionId;

    // Required — the amount the customer is paying now. Must be > 0 (partial or full).
    private BigDecimal amountTendered;

    // Optional — payment method for the initial payment (only used if amountTendered > 0)
    private PaymentMethod paymentMethod;

    // Optional — GCash mobile number for the initial payment
    private String gcashNumber;

    // Optional — reference number for the initial payment
    private String referenceNumber;

    @NotEmpty(message = "At least one item is required")
    private List<@NotNull @Valid TransactionItemsRequestDTO> items;

    private LocalDate estimatedReadyDate;

    // Senior/PWD identification (optional)
    private String seniorPwdName;
    private String seniorPwdAddress;
    private String seniorPwdIdNumber;
    private String seniorPwdType;
}
