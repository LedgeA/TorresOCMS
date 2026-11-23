package com.mmtorresoptical.OpticalClinicManagementSystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmtorresoptical.OpticalClinicManagementSystem.services.controller.DatabaseBackupService;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${CLINIC_TEST_DATABASE_URL}",
        "spring.datasource.username=${CLINIC_TEST_DATABASE_USERNAME}",
        "spring.datasource.password=${CLINIC_TEST_DATABASE_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=synthetic-clinic-test-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJ",
        "encryption.secret-key=00112233445566778899aabbccddeeff",
        "hash.pepper=synthetic-clinic-test-pepper"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CLINIC_TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClinicWorkflowTests {

    private static final AtomicInteger CONTACT_SEQUENCE = new AtomicInteger(100_000_000);

    @DynamicPropertySource
    static void isolateTestData(DynamicPropertyRegistry properties) throws IOException {
        String schema = "clinic_test_" + UUID.randomUUID().toString().replace("-", "");
        Path files = Files.createTempDirectory("clinic-workflow-");
        files.toFile().deleteOnExit();
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
        // Native inventory queries use the private tables and the shared PostgreSQL extension.
        properties.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO " + schema + ", public");
        properties.add("app.upload.product-images-dir", files::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private DatabaseBackupService backups;

    private String admin;

    @BeforeAll
    void signInAsAdmin() throws Exception {
        admin = token("admin", "admin123");
        completePasswordSetup(admin, "AdminFixture9!");
    }

    @Test
    void staffCanFindPatientsAtCheckoutButCannotReadTheirClinicalRecords() throws Exception {
        String patient = createPatient();
        String staff = createStaff();

        mvc.perform(get("/api/admin/patients/search").param("keyword", "Audit").header("Authorization", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].patientId", hasItem(patient)));
        mvc.perform(get("/api/admin/patients/{id}", patient).header("Authorization", staff))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/patients/00000000-0000-0000-0000-000000000001/visits",
            "/api/follow-ups/patient/00000000-0000-0000-0000-000000000001",
            "/api/audit",
            "/api/reports/data/PATIENTS",
            "/api/reports/excel/PATIENTS",
            "/api/transactions/accounts-receivable"
    })
    void staffCannotOpenAdminDatasets(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", createStaff()))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCanReadInventorySummaryAndTransactionList() throws Exception {
        String staff = createStaff();
        mvc.perform(get("/api/reports/inventory/summary").header("Authorization", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").isNumber());
        mvc.perform(get("/api/transactions").header("Authorization", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void staffCannotCreateProducts() throws Exception {
        mvc.perform(multipart("/api/products").file(productPart("""
                {"productName":"Unauthorized fixture","productType":"SERVICE","unitPrice":250,
                 "newCategoryName":"Unauthorized service category"}
                """)).header("Authorization", createStaff()))
                .andExpect(status().isForbidden());
    }

    @Test
    void checkoutShowsProductRecommendationsWithoutClinicalFields() throws Exception {
        String patient = createPatient();
        String product = createPhysicalProduct();
        String prescription = body(request(post("/api/admin/patient/" + patient + "/prescriptions"), admin, """
                {"issueDate":"%s","notes":"Synthetic confidential note","lensSpecifications":[],
                 "products":[{"productId":"%s","quantity":2,"staffNotes":"Synthetic clinical context"}]}
                """.formatted(LocalDate.now(), product)).andExpect(status().isCreated())).path("prescriptionId").asText();
        String staff = createStaff();
        mvc.perform(get("/api/patient/{id}/prescriptions", patient).header("Authorization", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].prescriptionId").value(prescription))
                .andExpect(jsonPath("$.content[0].notes").doesNotHaveJsonPath());
        mvc.perform(get("/api/prescriptions/{id}/checkout-recommendations", prescription)
                        .header("Authorization", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(product))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].staffNotes").doesNotHaveJsonPath());
        mvc.perform(get("/api/prescriptions/{id}", prescription).header("Authorization", staff))
                .andExpect(status().isForbidden());
    }

    @Test
    void checkoutRejectsAPrescriptionFromADifferentPatient() throws Exception {
        String patient = createPatient();
        String otherPatient = createPatient();
        String product = createPhysicalProduct();
        addStock(product, "PATIENT_LINK", 2, LocalDate.now().plusDays(5));
        String prescription = body(request(post("/api/admin/patient/" + patient + "/prescriptions"), admin, """
                {"issueDate":"%s","products":[{"productId":"%s","quantity":1}]}
                """.formatted(LocalDate.now(), product)).andExpect(status().isCreated()))
                .path("prescriptionId").asText();

        request(post("/api/transactions"), admin, """
                {"patientId":"%s","prescriptionId":"%s","amountTendered":100,"paymentMethod":"CASH",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(otherPatient, prescription, product)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/{id}/batches", product).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(2));
    }

    @Test
    void archivedAccountIsRejectedAndRestoredAccountCanSignIn() throws Exception {
        String username = "archive_" + unique();
        String userId = createUser(username);
        mvc.perform(delete("/api/admin/users/{id}", userId).header("Authorization", admin))
                .andExpect(status().is2xxSuccessful());
        request(post("/api/auth/login"), null, credentials(username, "FixturePass9!"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/users/{id}/restore", userId).header("Authorization", admin))
                .andExpect(status().is2xxSuccessful());
        assertThat(token(username, "FixturePass9!")).startsWith("Bearer ");
    }

    @Test
    void temporaryPasswordRequiresSetupBeforeStaffCanUseInventory() throws Exception {
        String username = "reset_" + unique();
        String user = createUser(username);
        request(put("/api/auth/admin/reset-password/" + user), admin, """
                {"temporaryPassword":"Temporary9!"}
                """).andExpect(status().isOk());

        String temporary = token(username, "Temporary9!");
        mvc.perform(get("/api/products").header("Authorization", temporary))
                .andExpect(status().isForbidden());
        request(post("/api/auth/enforce-password-change"), temporary, """
                {"newPassword":"Replacement9!","securityQuestion":"What is your favorite color?",
                 "securityAnswer":"Blue"}
                """).andExpect(status().isOk());
        mvc.perform(get("/api/products").header("Authorization", token(username, "Replacement9!")))
                .andExpect(status().isOk());
    }

    @Test
    void selfServiceRecoveryChangesThePasswordAfterTheCorrectAnswer() throws Exception {
        String username = "recovery_" + unique();
        createUser(username);
        completePasswordSetup(token(username, "FixturePass9!"), "StaffFixture9!");
        String email = username + "@example.test";

        request(post("/api/auth/forgot-password/question"), null, """
                {"email":"%s"}
                """.formatted(email)).andExpect(status().isOk())
                .andExpect(jsonPath("$.securityQuestion").value("What is your favorite color?"));
        request(post("/api/auth/forgot-password/reset"), null, """
                {"email":"%s","securityAnswer":"Wrong answer","newPassword":"Recovered9!"}
                """.formatted(email)).andExpect(status().isBadRequest());
        request(post("/api/auth/forgot-password/reset"), null, """
                {"email":"%s","securityAnswer":"Blue","newPassword":"Recovered9!"}
                """.formatted(email)).andExpect(status().isOk());

        request(post("/api/auth/login"), null, credentials(username, "StaffFixture9!"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", token(username, "Recovered9!")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void adminCanRecordAnExamAndScheduleAVisitLinkedToThePrescription() throws Exception {
        String patient = createPatient();
        String product = createPhysicalProduct();
        String exam = body(request(post("/api/admin/patients/" + patient + "/eye-exams"), admin, """
                {"examType":"MANUAL","chiefComplaint":"Synthetic blurred vision"}
                """).andExpect(status().isCreated())).path("eyeExamId").asText();

        String prescription = body(request(post("/api/admin/patient/" + patient + "/prescriptions"), admin, """
                {"issueDate":"%s","eyeExamId":"%s","notes":"Synthetic prescription",
                 "products":[{"productId":"%s","quantity":1}]}
                """.formatted(LocalDate.now(), exam, product)).andExpect(status().isCreated()))
                .path("prescriptionId").asText();

        LocalDate followUpDate = LocalDate.now().plusDays(7);
        String followUp = body(request(post("/api/follow-ups"), admin, """
                {"patientId":"%s","scheduledDate":"%s","prescriptionId":"%s","followUpReason":"Recheck"}
                """.formatted(patient, followUpDate, prescription)).andExpect(status().isCreated()))
                .path("followUpId").asText();
        mvc.perform(get("/api/follow-ups/patient/{id}", patient).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].followUpId").value(followUp))
                .andExpect(jsonPath("$.content[0].prescriptionId").value(prescription))
                .andExpect(jsonPath("$.content[0].scheduledDate").value(followUpDate.toString()));
        LocalDate rescheduled = followUpDate.plusDays(2);
        request(patch("/api/follow-ups/" + followUp + "/reschedule"), admin, """
                {"scheduledDate":"%s"}
                """.formatted(rescheduled)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledDate").value(rescheduled.toString()));
    }

    @Test
    void eyeExamVoidRetainsTheOriginalRecordAndReason() throws Exception {
        String patient = createPatient();
        String exam = body(request(post("/api/admin/patients/" + patient + "/eye-exams"), admin, """
                {"examType":"MANUAL","chiefComplaint":"Synthetic examination"}
                """).andExpect(status().isCreated())).path("eyeExamId").asText();
        request(post("/api/admin/eye-exams/" + exam + "/void"), admin, """
                {"voidReason":"Duplicate examination"}
                """).andExpect(status().isOk());

        mvc.perform(get("/api/admin/eye-exams/{id}", exam).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eyeExamId").value(exam))
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.voidReason").value("Duplicate examination"));
    }

    @Test
    void patientMaintenanceRestoresTheSamePatientRecord() throws Exception {
        String patient = createPatient();
        mvc.perform(delete("/api/admin/patients/{id}", patient).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/patients").param("keyword", patient).param("archivedStatus", "ACTIVE")
                        .header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(put("/api/admin/patients/{id}/restore", patient).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/patients").param("keyword", patient).param("archivedStatus", "ACTIVE")
                        .header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].patientId").value(patient));
    }

    @Test
    void saleAllocatesEarlierExpiringStockAndPaymentAndRefundReconcile() throws Exception {
        String product = createPhysicalProduct();
        addStock(product, "LATER", 4, LocalDate.now().plusDays(10));
        addStock(product, "EARLIER", 3, LocalDate.now().plusDays(3));
        String patient = createPatient();
        String prescription = createSalePrescription(patient, product);

        JsonNode sale = body(request(post("/api/transactions"), admin, """
                {"patientId":"%s","prescriptionId":"%s","amountTendered":150,"paymentMethod":"CASH",
                 "items":[{"productId":"%s","quantity":2}]}
                """.formatted(patient, prescription, product)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(200))
                .andExpect(jsonPath("$.balanceDue").value(50))
                .andExpect(jsonPath("$.transactionItems[0].batchAllocations[0].batchNumber").value("EARLIER")));

        String saleId = sale.path("transactionId").asText();
        request(post("/api/transactions/" + saleId + "/payments"), admin, """
                {"amount":50,"paymentMethod":"CASH"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(50));

        String lineId = sale.at("/transactionItems/0/transactionItemId").asText();
        request(post("/api/transactions/refund"), admin, """
                {"refundMethod":"CASH","items":[{"transactionItemId":"%s","refundQuantity":1,
                "refundReason":"CHANGE_OF_MIND"}]}
                """.formatted(lineId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cashToReturn").value(100))
                .andExpect(jsonPath("$.newRemainingDue").value(0));
        mvc.perform(get("/api/transactions/{id}", saleId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionItems[0].refundedQuantity").value(1))
                .andExpect(jsonPath("$.payments.length()").value(2));
    }

    @Test
    void rejectsEmptyCheckoutBeforeCreatingASale() throws Exception {
        request(post("/api/transactions"), admin, """
                {"amountTendered":100,"paymentMethod":"CASH","items":[]}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.items").exists());
    }

    @Test
    void rejectsZeroQuantityBeforeCreatingASale() throws Exception {
        String product = createPhysicalProduct();
        request(post("/api/transactions"), admin, """
                {"amountTendered":100,"paymentMethod":"CASH","items":[{"productId":"%s","quantity":0}]}
                """.formatted(product)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['items[0].quantity']").exists());
    }

    @Test
    void walkInCheckoutCannotCreateADeposit() throws Exception {
        String product = createPhysicalProduct();
        addStock(product, "WALK_IN", 1, LocalDate.now().plusDays(5));

        request(post("/api/transactions"), admin, """
                {"amountTendered":60,"paymentMethod":"CASH","items":[{"productId":"%s","quantity":1}]}
                """.formatted(product)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/{id}/batches", product).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(1));
    }

    @Test
    void patientCheckoutWithoutAPrescriptionCannotCreateADeposit() throws Exception {
        String patient = createPatient();
        String product = createPhysicalProduct();
        addStock(product, "NO_PRESCRIPTION", 1, LocalDate.now().plusDays(5));

        request(post("/api/transactions"), admin, """
                {"patientId":"%s","amountTendered":60,"paymentMethod":"CASH",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(patient, product)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/{id}/batches", product).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(1));
    }

    @Test
    void patientCheckoutWithAVoidedPrescriptionCannotCreateADeposit() throws Exception {
        String patient = createPatient();
        String product = createPhysicalProduct();
        addStock(product, "VOIDED_PRESCRIPTION", 1, LocalDate.now().plusDays(5));
        String prescription = createSalePrescription(patient, product);
        request(post("/api/admin/prescriptions/" + prescription + "/void"), admin, """
                {"voidReason":"Prescription entered in error"}
                """).andExpect(status().isOk());

        request(post("/api/transactions"), admin, """
                {"patientId":"%s","prescriptionId":"%s","amountTendered":60,"paymentMethod":"CASH",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(patient, prescription, product)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/{id}/batches", product).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(1));
    }

    @Test
    void gcashSaleRecordsTheSubmittedPaymentReference() throws Exception {
        String product = createPhysicalProduct();
        addStock(product, "GCASH", 1, LocalDate.now().plusDays(5));
        request(post("/api/transactions"), admin, """
                {"amountTendered":100,"paymentMethod":"GCASH","gcashNumber":"09112223333",
                 "referenceNumber":"1002300001","items":[{"productId":"%s","quantity":1}]}
                """.formatted(product)).andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("PAID"))
                .andExpect(jsonPath("$.payments[0].amount").value(100))
                .andExpect(jsonPath("$.payments[0].paymentMethod").value("GCASH"))
                .andExpect(jsonPath("$.payments[0].referenceNumber").value("1002300001"));
    }

    @Test
    void repeatedProductLinesUseTheRemainingQuantityInEachBatch() throws Exception {
        String product = createPhysicalProduct();
        addStock(product, "FIRST", 3, LocalDate.now().plusDays(3));
        addStock(product, "SECOND", 5, LocalDate.now().plusDays(10));
        request(post("/api/transactions"), admin, """
                {"amountTendered":400,"paymentMethod":"CASH","items":[
                 {"productId":"%s","quantity":2},{"productId":"%s","quantity":2}]}
                """.formatted(product, product)).andExpect(status().isOk());
        mvc.perform(get("/api/products/{id}/batches", product).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[?(@.batchNumber == 'FIRST')].quantityRemaining", hasItem(0)))
                .andExpect(jsonPath("$.batches[?(@.batchNumber == 'SECOND')].quantityRemaining", hasItem(4)));
    }

    @Test
    void archivedProductIsExcludedFromActiveInventoryUntilRestored() throws Exception {
        String product = createPhysicalProduct();
        mvc.perform(delete("/api/products/{id}", product).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/products").param("archivedStatus", "ACTIVE")
                        .param("keyword", product).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());

        mvc.perform(put("/api/products/{id}/restore", product).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/products").param("archivedStatus", "ACTIVE")
                        .param("keyword", product).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].productId").value(product));
    }

    @Test
    void inventorySearchFindsAProductWithATypo() throws Exception {
        String product = createPhysicalProduct();
        String name = body(mvc.perform(get("/api/products/{id}", product).header("Authorization", admin))
                .andExpect(status().isOk())).path("productName").asText();
        String typo = "z" + name.substring(1);
        mvc.perform(get("/api/products").param("keyword", typo).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].productId", hasItem(product)));
    }

    @Test
    void pickupRequiresFullPayment() throws Exception {
        JsonNode sale = makeDepositSale();
        String path = "/api/transactions/" + sale.path("transactionId").asText();
        request(patch(path + "/fulfillment"), admin, """
                {"fulfillmentStatus":"FOR_PICKUP"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.fulfillmentStatus").value("FOR_PICKUP"));
        request(patch(path + "/fulfillment"), admin, """
                {"fulfillmentStatus":"COMPLETED"}
                """).andExpect(status().isBadRequest());
        request(post(path + "/payments"), admin, """
                {"amount":500,"paymentMethod":"CASH"}
                """).andExpect(status().isOk());
        request(patch(path + "/fulfillment"), admin, """
                {"fulfillmentStatus":"COMPLETED"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void returnCreditsTheFullItemValueAndOnlyPaysBackTheSurplus() throws Exception {
        JsonNode sale = makeDepositSale();
        request(post("/api/transactions/refund"), admin, refundRequest(sale, 6)).andExpect(status().isOk())
                .andExpect(jsonPath("$.newOrderTotal").value(400))
                .andExpect(jsonPath("$.cashToReturn").value(100))
                .andExpect(jsonPath("$.amountPaid").value(400))
                .andExpect(jsonPath("$.newRemainingDue").value(0));
    }

    @Test
    void paymentCanSettleTheReducedBalanceAfterAReturn() throws Exception {
        JsonNode sale = makeDepositSale();
        String id = sale.path("transactionId").asText();
        request(post("/api/transactions/refund"), admin, refundRequest(sale, 3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cashToReturn").value(0))
                .andExpect(jsonPath("$.newRemainingDue").value(200));
        request(post("/api/transactions/" + id + "/payments"), admin, """
                {"amount":200,"paymentMethod":"CASH"}
                """).andExpect(status().isOk());
        mvc.perform(get("/api/transactions/{id}", id).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceDue").value(0))
                .andExpect(jsonPath("$.transactionStatus").value("PAID"));
    }

    private JsonNode makeDepositSale() throws Exception {
        String product = createPhysicalProduct();
        addStock(product, "DEPOSIT", 10, LocalDate.now().plusDays(5));
        String patient = createPatient();
        String prescription = createSalePrescription(patient, product);
        return body(request(post("/api/transactions"), admin, """
                {"patientId":"%s","prescriptionId":"%s","amountTendered":500,"paymentMethod":"CASH",
                 "items":[{"productId":"%s","quantity":10}]}
                """.formatted(patient, prescription, product)).andExpect(status().isOk()));
    }

    private String createSalePrescription(String patient, String product) throws Exception {
        return body(request(post("/api/admin/patient/" + patient + "/prescriptions"), admin, """
                {"issueDate":"%s","products":[{"productId":"%s","quantity":1}]}
                """.formatted(LocalDate.now(), product)).andExpect(status().isCreated()))
                .path("prescriptionId").asText();
    }

    private static String refundRequest(JsonNode sale, int quantity) {
        return """
                {"refundMethod":"CASH","items":[{"transactionItemId":"%s","refundQuantity":%d,
                "refundReason":"Change of mind"}]}
                """.formatted(sale.at("/transactionItems/0/transactionItemId").asText(), quantity);
    }

    @Test
    void patientExcelExportContainsClinicData() throws Exception {
        createPatient();
        byte[] content = mvc.perform(get("/api/reports/excel/PATIENTS").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(workbook.getSheetAt(0).getPhysicalNumberOfRows()).isGreaterThan(1);
        }
    }

    private String createPatient() throws Exception {
        String nameSuffix = UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        return body(request(post("/api/admin/patients"), admin, """
                {"firstName":"Audit","lastName":"Patient%s","sex":"FEMALE",
                 "email":"patient-%s@example.test","contactNumber":"09%d",
                 "birthDate":"1997-02-03","address":"Synthetic address"}
                """.formatted(nameSuffix, unique(), CONTACT_SEQUENCE.incrementAndGet()))
                .andExpect(status().isCreated()))
                .path("patientId").asText();
    }

    private String createStaff() throws Exception {
        String username = "staff_" + unique();
        createUser(username);
        String staff = token(username, "FixturePass9!");
        completePasswordSetup(staff, "StaffFixture9!");
        return staff;
    }

    private void completePasswordSetup(String token, String password) throws Exception {
        request(post("/api/auth/enforce-password-change"), token, """
                {"newPassword":"%s","securityQuestion":"What is your favorite color?",
                 "securityAnswer":"Blue"}
                """.formatted(password)).andExpect(status().isOk());
    }

    private String createUser(String username) throws Exception {
        return body(request(post("/api/admin/users"), admin, """
                {"firstName":"Sample","lastName":"Staff","sex":"FEMALE","birthDate":"1995-01-01",
                 "email":"%s@example.test","contactNumber":"09%d","username":"%s",
                 "password":"FixturePass9!","role":"STAFF"}
                """.formatted(username, CONTACT_SEQUENCE.incrementAndGet(), username)).andExpect(status().isCreated())).path("userId").asText();
    }

    private String createPhysicalProduct() throws Exception {
        String category = body(mvc.perform(post("/api/categories")
                .param("name", "Test " + unique()).param("type", "PHYSICAL").param("isPerishable", "true")
                .header("Authorization", admin)).andExpect(status().is2xxSuccessful())).path("categoryId").asText();

        return body(mvc.perform(multipart("/api/products").file(productPart("""
                {"productName":"Test frame %s","productType":"PHYSICAL","unitPrice":100,
                 "categoryId":"%s","newSupplierName":"Test supplier %s",
                 "lowLevelThreshold":1,"overstockedThreshold":20}
                """.formatted(unique(), category, unique()))).header("Authorization", admin))
                .andExpect(status().isCreated())).path("productId").asText();
    }

    private void addStock(String product, String batch, int quantity, LocalDate expiry) throws Exception {
        request(post("/api/products/" + product + "/add-stock"), admin, """
                {"batchNumber":"%s","quantity":%d,"expiryDate":"%s","reason":"Fixture stocking"}
                """.formatted(batch, quantity, expiry)).andExpect(status().is2xxSuccessful());
    }

    private String token(String username, String password) throws Exception {
        JsonNode login = body(request(post("/api/auth/login"), null, credentials(username, password))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString()));
        return "Bearer " + login.path("accessToken").asText();
    }

    private static String credentials(String username, String password) {
        return """
                {"loginIdentifier":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private static MockMultipartFile productPart(String body) {
        return new MockMultipartFile("product", "", "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions request(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) {
            request.header("Authorization", token);
        }
        return mvc.perform(request.contentType("application/json").content(body));
    }

    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray());
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
