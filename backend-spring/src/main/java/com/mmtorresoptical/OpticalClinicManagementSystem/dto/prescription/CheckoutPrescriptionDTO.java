package com.mmtorresoptical.OpticalClinicManagementSystem.dto.prescription;

import java.time.LocalDate;
import java.util.UUID;

public record CheckoutPrescriptionDTO(UUID prescriptionId, String rxNumber, LocalDate issueDate) {}
