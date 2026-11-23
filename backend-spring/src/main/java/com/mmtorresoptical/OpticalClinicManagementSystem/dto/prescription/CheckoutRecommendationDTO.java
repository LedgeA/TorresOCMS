package com.mmtorresoptical.OpticalClinicManagementSystem.dto.prescription;

import com.mmtorresoptical.OpticalClinicManagementSystem.enums.ProductType;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutRecommendationDTO(
        UUID productId,
        String productName,
        ProductType productType,
        BigDecimal unitPrice,
        int stockQuantity,
        int quantity,
        boolean isSeniorPwdEligible,
        boolean isArchived
) {}
