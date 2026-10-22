package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Appointment;
import com.gmc.backend.model.AppointmentStatus;
import com.gmc.backend.model.Complaint;
import com.gmc.backend.model.CustomDesign;
import com.gmc.backend.model.CustomDesignStatus;
import com.gmc.backend.model.Feedback;
import com.gmc.backend.model.Order;
import com.gmc.backend.model.OrderItem;
import com.gmc.backend.model.OrderStatus;
import com.gmc.backend.model.PaymentMethod;
import com.gmc.backend.model.PaymentStatus;
import com.gmc.backend.model.Product;
import com.gmc.backend.model.ProductCategory;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.AppointmentRepository;
import com.gmc.backend.repository.ComplaintRepository;
import com.gmc.backend.repository.CustomDesignRepository;
import com.gmc.backend.repository.FeedbackRepository;
import com.gmc.backend.repository.OrderRepository;
import com.gmc.backend.repository.ProductCategoryRepository;
import com.gmc.backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class AdminControllerIT extends IntegrationTestBase {

    /** Smallest valid PNG (1x1 transparent pixel). */
    private static final byte[] PNG = {
        (byte) 0x89,
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x08,
        0x06,
        0x00,
        0x00,
        0x00,
        0x1F,
        0x15,
        (byte) 0xC4,
        (byte) 0x89,
        0x00,
        0x00,
        0x00,
        0x0A,
        0x49,
        0x44,
        0x41,
        0x54,
        0x78,
        (byte) 0x9C,
        0x63,
        0x00,
        0x01,
        0x00,
        0x00,
        0x05,
        0x00,
        0x01,
        0x0D,
        0x0A,
        0x2D,
        (byte) 0xB4,
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4E,
        0x44,
        (byte) 0xAE,
        0x42,
        0x60,
        (byte) 0x82
    };

    private static final long MISSING_ID = 999_999L;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private CustomDesignRepository customDesignRepository;

    private User admin;
    private String adminAuth;
    private User customer;

    @BeforeEach
    void setUp() {
        admin = createAdmin();
        adminAuth = bearer(admin);
        customer = createCustomer();
    }

    // ── Seed helpers ──────────────────────────────────────────────────────────

    private ProductCategory category() {
        return categoryRepository.save(ProductCategory.builder()
                .name("Category " + UUID.randomUUID().toString().substring(0, 8))
                .build());
    }

    private Product product(int stock, boolean active) {
        return productRepository.save(Product.builder()
                .name("Teak Table " + UUID.randomUUID().toString().substring(0, 8))
                .description("Solid teak dining table")
                .price(new BigDecimal("1000.00"))
                .category(category())
                .stockQuantity(stock)
                .isActive(active)
                .build());
    }

    private Order order(OrderStatus status) {
        Product product = product(10, true);
        Order order = Order.builder()
                .user(customer)
                .orderDate(LocalDateTime.now())
                .status(status)
                .paymentMethod(PaymentMethod.CASH_ON_DELIVERY)
                .paymentStatus(PaymentStatus.PENDING)
                .subtotal(new BigDecimal("1000.00"))
                .shippingFee(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("1000.00"))
                .shippingName(customer.getName())
                .shippingPhone("0771234567")
                .shippingAddress("12 Main Street")
                .shippingCity("Colombo")
                .shippingPostalCode("00100")
                .build();
        order.getItems()
                .add(OrderItem.builder()
                        .order(order)
                        .product(product)
                        .quantity(1)
                        .unitPrice(new BigDecimal("1000.00"))
                        .build());
        return orderRepository.save(order);
    }

    private Appointment appointment(AppointmentStatus status, LocalDateTime date) {
        return appointmentRepository.save(Appointment.builder()
                .user(customer)
                .category(category())
                .date(date)
                .message("I'd like a custom wardrobe")
                .status(status)
                .build());
    }

    private Feedback feedback() {
        Order order = order(OrderStatus.DELIVERED);
        return feedbackRepository.save(Feedback.builder()
                .user(customer)
                .order(order)
                .product(order.getItems().get(0).getProduct())
                .rating(4)
                .comment("Lovely finish")
                .createdAt(LocalDateTime.now())
                .hidden(false)
                .build());
    }

    private Complaint complaint() {
        return complaintRepository.save(Complaint.builder()
                .user(customer)
                .description("The table arrived scratched")
                .date(LocalDateTime.now())
                .status("OPEN")
                .build());
    }

    private CustomDesign design() {
        return customDesignRepository.save(CustomDesign.builder()
                .user(customer)
                .designName("Curved bookshelf")
                .description("Oak, 2m tall")
                .status(CustomDesignStatus.PENDING_REVIEW)
                .createdDate(LocalDateTime.now())
                .build());
    }

    // ── Access control ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/admin/dashboard",
                "/api/admin/users",
                "/api/admin/orders",
                "/api/admin/appointments",
                "/api/admin/feedback",
                "/api/admin/complaints",
                "/api/admin/custom-designs",
                "/api/admin/invoices",
                "/api/admin/maintenance",
                "/api/admin/products"
            })
    void adminEndpoints_withCustomerToken_return403(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", bearer(customer))).andExpect(status().isForbidden());
    }

    @Test
    void adminEndpoint_withTechnicianToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", bearer(createTechnician()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sofas"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpoint_withoutToken_isDenied() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard")).andExpect(status().isForbidden());
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    @Test
    void getDashboard_withOrdersAndLowStock_returnsStats() throws Exception {
        order(OrderStatus.PENDING);
        order(OrderStatus.DELIVERED);
        Product lowStock = product(2, true);
        appointment(AppointmentStatus.APPROVED, LocalDateTime.now().withHour(23).withMinute(30));

        mockMvc.perform(get("/api/admin/dashboard").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.pendingOrders", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalRevenue", greaterThanOrEqualTo(1000.0)))
                .andExpect(jsonPath("$.appointmentsToday", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.lowStockProducts[*].name", hasItem(lowStock.getName())))
                .andExpect(jsonPath("$.totalUsers", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalProducts", greaterThanOrEqualTo(3)));
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    @Test
    void getUsers_withoutFilter_listsAllUsers() throws Exception {
        mockMvc.perform(get("/api/admin/users").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem(customer.getEmail())))
                .andExpect(jsonPath("$[*].email", hasItem(admin.getEmail())));
    }

    @Test
    void getUsers_withRoleFilter_listsOnlyThatRole() throws Exception {
        User technician = createTechnician();

        mockMvc.perform(get("/api/admin/users").param("role", "technician").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem(technician.getEmail())))
                .andExpect(jsonPath("$[*].role", everyItem(is("TECHNICIAN"))));
    }

    @Test
    void createUser_withValidTechnician_returns201() throws Exception {
        Map<String, Object> body = Map.of(
                "name",
                "Kamal Silva",
                "email",
                "Kamal.Tech@Example.com",
                "password",
                PASSWORD,
                "phone",
                "0779876543",
                "role",
                "technician");

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("kamal.tech@example.com"))
                .andExpect(jsonPath("$.role").value("TECHNICIAN"))
                .andExpect(jsonPath("$.active").value(true));

        User saved = userRepository.findByEmail("kamal.tech@example.com").orElseThrow();
        assertThat(saved.getPassword()).startsWith("$2");
    }

    @Test
    void createUser_withExistingEmail_returns409() throws Exception {
        Map<String, Object> body =
                Map.of("name", "Copy", "email", customer.getEmail(), "password", PASSWORD, "role", "CUSTOMER");

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void createUser_withUnknownRoleAndShortPassword_returns400() throws Exception {
        Map<String, Object> body =
                Map.of("name", "X", "email", "x@example.com", "password", "short", "role", "SUPERUSER");

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivateUser_forAnotherUser_marksInactive() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/deactivate", customer.getUserId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(userRepository.findById(customer.getUserId()).orElseThrow().getActive())
                .isFalse();
    }

    @Test
    void deactivateUser_forOwnAccount_returns422() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/deactivate", admin.getUserId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deactivateUser_whenUserMissing_returns404() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/deactivate", MISSING_ID).header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
    }

    @Test
    void activateUser_forInactiveUser_marksActive() throws Exception {
        customer.setActive(false);
        userRepository.save(customer);

        mockMvc.perform(patch("/api/admin/users/{id}/activate", customer.getUserId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void activateUser_whenUserMissing_returns404() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/activate", MISSING_ID).header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    @Test
    void getAllOrders_withoutFilter_listsOrders() throws Exception {
        Order order = order(OrderStatus.PENDING);

        mockMvc.perform(get("/api/admin/orders").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].orderId", hasItem(order.getOrderId().intValue())));
    }

    @Test
    void getAllOrders_withStatusFilter_listsOnlyThatStatus() throws Exception {
        Order shipped = order(OrderStatus.SHIPPED);
        order(OrderStatus.PENDING);

        mockMvc.perform(get("/api/admin/orders").param("status", "SHIPPED").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].orderId", hasItem(shipped.getOrderId().intValue())))
                .andExpect(jsonPath("$[*].status", everyItem(is("SHIPPED"))));
    }

    @Test
    void getAllOrders_withUnknownStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("status", "LOST").header("Authorization", adminAuth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateOrderStatus_pendingCashOrderToProcessing_returnsUpdatedOrder() throws Exception {
        Order order = order(OrderStatus.PENDING);

        mockMvc.perform(put("/api/admin/orders/{id}/status", order.getOrderId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "PROCESSING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void updateOrderStatus_shippedCashOrderToDelivered_marksPaid() throws Exception {
        Order order = order(OrderStatus.SHIPPED);

        mockMvc.perform(put("/api/admin/orders/{id}/status", order.getOrderId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DELIVERED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"));
    }

    @Test
    void updateOrderStatus_skippingSteps_returns422() throws Exception {
        Order order = order(OrderStatus.PENDING);

        mockMvc.perform(put("/api/admin/orders/{id}/status", order.getOrderId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DELIVERED"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateOrderStatus_fromDelivered_returns409() throws Exception {
        Order order = order(OrderStatus.DELIVERED);

        mockMvc.perform(put("/api/admin/orders/{id}/status", order.getOrderId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "CANCELLED"))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateOrderStatus_withoutStatus_returns400() throws Exception {
        Order order = order(OrderStatus.PENDING);

        mockMvc.perform(put("/api/admin/orders/{id}/status", order.getOrderId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateOrderStatus_whenOrderMissing_returns404() throws Exception {
        mockMvc.perform(put("/api/admin/orders/{id}/status", MISSING_ID)
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "PROCESSING"))))
                .andExpect(status().isNotFound());
    }

    // ── Appointments ──────────────────────────────────────────────────────────

    @Test
    void getAllAppointments_withoutFilter_listsAppointments() throws Exception {
        Appointment appointment =
                appointment(AppointmentStatus.PENDING, LocalDateTime.now().plusDays(3));

        mockMvc.perform(get("/api/admin/appointments").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[*].appointmentId",
                        hasItem(appointment.getAppointmentId().intValue())));
    }

    @Test
    void getAllAppointments_withStatusFilter_listsOnlyThatStatus() throws Exception {
        Appointment approved =
                appointment(AppointmentStatus.APPROVED, LocalDateTime.now().plusDays(3));
        appointment(AppointmentStatus.PENDING, LocalDateTime.now().plusDays(4));

        mockMvc.perform(get("/api/admin/appointments")
                        .param("status", "APPROVED")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[*].appointmentId",
                        hasItem(approved.getAppointmentId().intValue())))
                .andExpect(jsonPath("$[*].status", everyItem(is("APPROVED"))));
    }

    @Test
    void updateAppointmentStatus_approvePending_setsMeetingLink() throws Exception {
        Appointment appointment =
                appointment(AppointmentStatus.PENDING, LocalDateTime.now().plusDays(3));
        Map<String, Object> body =
                Map.of("status", "APPROVED", "meetingLink", "https://meet.example.com/abc", "note", "See you then");

        mockMvc.perform(put("/api/admin/appointments/{id}/status", appointment.getAppointmentId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.meetingLink").value("https://meet.example.com/abc"))
                .andExpect(jsonPath("$.adminNote").value("See you then"));
    }

    @Test
    void updateAppointmentStatus_withNonHttpMeetingLink_returns422() throws Exception {
        Appointment appointment =
                appointment(AppointmentStatus.PENDING, LocalDateTime.now().plusDays(3));
        Map<String, Object> body = Map.of("status", "APPROVED", "meetingLink", "javascript:alert(1)");

        mockMvc.perform(put("/api/admin/appointments/{id}/status", appointment.getAppointmentId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateAppointmentStatus_backToPending_returns422() throws Exception {
        Appointment appointment =
                appointment(AppointmentStatus.APPROVED, LocalDateTime.now().plusDays(3));

        mockMvc.perform(put("/api/admin/appointments/{id}/status", appointment.getAppointmentId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "PENDING"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateAppointmentStatus_whenAppointmentMissing_returns404() throws Exception {
        mockMvc.perform(put("/api/admin/appointments/{id}/status", MISSING_ID)
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "CANCELLED"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rescheduleAppointment_toFutureDate_setsRescheduled() throws Exception {
        Appointment appointment =
                appointment(AppointmentStatus.PENDING, LocalDateTime.now().plusDays(3));
        LocalDateTime newDate = LocalDateTime.now().plusDays(10).withNano(0);
        Map<String, Object> body = Map.of("newDate", newDate.toString(), "note", "Workshop closed that day");

        mockMvc.perform(put("/api/admin/appointments/{id}/reschedule", appointment.getAppointmentId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESCHEDULED"))
                .andExpect(jsonPath("$.date", startsWith(newDate.toLocalDate().toString())));
    }

    @Test
    void rescheduleAppointment_toPastDate_returns400() throws Exception {
        Appointment appointment =
                appointment(AppointmentStatus.PENDING, LocalDateTime.now().plusDays(3));
        Map<String, Object> body =
                Map.of("newDate", LocalDateTime.now().minusDays(1).withNano(0).toString());

        mockMvc.perform(put("/api/admin/appointments/{id}/reschedule", appointment.getAppointmentId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rescheduleAppointment_whenCompleted_returns422() throws Exception {
        Appointment appointment =
                appointment(AppointmentStatus.COMPLETED, LocalDateTime.now().minusDays(3));
        Map<String, Object> body =
                Map.of("newDate", LocalDateTime.now().plusDays(5).withNano(0).toString());

        mockMvc.perform(put("/api/admin/appointments/{id}/reschedule", appointment.getAppointmentId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Feedback & complaints ─────────────────────────────────────────────────

    @Test
    void getAllFeedback_listsReviews() throws Exception {
        Feedback feedback = feedback();

        mockMvc.perform(get("/api/admin/feedback").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[*].feedbackId", hasItem(feedback.getFeedbackId().intValue())));
    }

    @Test
    void setFeedbackVisibility_hidden_hidesReview() throws Exception {
        Feedback feedback = feedback();

        mockMvc.perform(patch("/api/admin/feedback/{id}/visibility", feedback.getFeedbackId())
                        .param("hidden", "true")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true));
    }

    @Test
    void setFeedbackVisibility_withoutHiddenParam_returns400() throws Exception {
        Feedback feedback = feedback();

        mockMvc.perform(patch("/api/admin/feedback/{id}/visibility", feedback.getFeedbackId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setFeedbackVisibility_whenReviewMissing_returns404() throws Exception {
        mockMvc.perform(patch("/api/admin/feedback/{id}/visibility", MISSING_ID)
                        .param("hidden", "true")
                        .header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAllComplaints_listsComplaints() throws Exception {
        Complaint complaint = complaint();

        mockMvc.perform(get("/api/admin/complaints").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[*].complaintId", hasItem(complaint.getComplaintId().intValue())));
    }

    @Test
    void updateComplaintStatus_toResolved_updatesStatus() throws Exception {
        Complaint complaint = complaint();

        mockMvc.perform(patch("/api/admin/complaints/{id}/status", complaint.getComplaintId())
                        .param("status", "resolved")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void updateComplaintStatus_withUnknownStatus_returns422() throws Exception {
        Complaint complaint = complaint();

        mockMvc.perform(patch("/api/admin/complaints/{id}/status", complaint.getComplaintId())
                        .param("status", "DONE")
                        .header("Authorization", adminAuth))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateComplaintStatus_whenComplaintMissing_returns404() throws Exception {
        mockMvc.perform(patch("/api/admin/complaints/{id}/status", MISSING_ID)
                        .param("status", "OPEN")
                        .header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
    }

    // ── Custom designs ────────────────────────────────────────────────────────

    @Test
    void getAllDesigns_listsDesigns() throws Exception {
        CustomDesign design = design();

        mockMvc.perform(get("/api/admin/custom-designs").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[*].designId", hasItem(design.getDesignId().intValue())));
    }

    @Test
    void updateDesignStatus_toApproved_updatesStatus() throws Exception {
        CustomDesign design = design();

        mockMvc.perform(patch("/api/admin/custom-designs/{id}/status", design.getDesignId())
                        .param("status", "APPROVED")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void updateDesignStatus_withUnknownStatus_returns400() throws Exception {
        CustomDesign design = design();

        mockMvc.perform(patch("/api/admin/custom-designs/{id}/status", design.getDesignId())
                        .param("status", "MAYBE")
                        .header("Authorization", adminAuth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateDesignStatus_whenDesignMissing_returns404() throws Exception {
        mockMvc.perform(patch("/api/admin/custom-designs/{id}/status", MISSING_ID)
                        .param("status", "APPROVED")
                        .header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
    }

    // ── Invoices ──────────────────────────────────────────────────────────────

    @Test
    void generateInvoice_forDeliveredOrder_returns201AndListsIt() throws Exception {
        Order order = order(OrderStatus.DELIVERED);

        mockMvc.perform(post("/api/admin/invoices/orders/{id}", order.getOrderId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(order.getOrderId()))
                .andExpect(jsonPath("$.amount").value(1000.0));

        mockMvc.perform(get("/api/admin/invoices").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].orderId", hasItem(order.getOrderId().intValue())));
    }

    @Test
    void generateInvoice_twiceForSameOrder_returns422() throws Exception {
        Order order = order(OrderStatus.DELIVERED);
        mockMvc.perform(
                post("/api/admin/invoices/orders/{id}", order.getOrderId()).header("Authorization", adminAuth));

        mockMvc.perform(post("/api/admin/invoices/orders/{id}", order.getOrderId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void generateInvoice_forCancelledOrder_returns422() throws Exception {
        Order order = order(OrderStatus.CANCELLED);

        mockMvc.perform(post("/api/admin/invoices/orders/{id}", order.getOrderId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void generateInvoice_whenOrderMissing_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/invoices/orders/{id}", MISSING_ID).header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    private Map<String, Object> maintenanceBody(Long technicianId) {
        Map<String, Object> body = new HashMap<>();
        body.put("description", "Build a custom teak wardrobe");
        body.put("title", "Teak wardrobe");
        body.put("technicianId", technicianId);
        body.put("deadline", LocalDate.now().plusDays(7).toString());
        body.put("cost", 25000);
        body.put("imageUrls", List.of("/uploads/maintenance/a.png"));
        return body;
    }

    @Test
    void assignMaintenance_toTechnician_returns201AndListsIt() throws Exception {
        User technician = createTechnician();

        mockMvc.perform(post("/api/admin/maintenance")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(maintenanceBody(technician.getUserId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.technicianId").value(technician.getUserId()))
                .andExpect(jsonPath("$.title").value("Teak wardrobe"))
                .andExpect(jsonPath("$.cost").value(25000));

        mockMvc.perform(get("/api/admin/maintenance").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("Teak wardrobe")));
    }

    @Test
    void assignMaintenance_toCustomer_returns422() throws Exception {
        mockMvc.perform(post("/api/admin/maintenance")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(maintenanceBody(customer.getUserId()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void assignMaintenance_toUnknownTechnician_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/maintenance")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(maintenanceBody(MISSING_ID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignMaintenance_withPastDeadlineAndNoCost_returns400() throws Exception {
        Map<String, Object> body = maintenanceBody(createTechnician().getUserId());
        body.put("deadline", LocalDate.now().minusDays(1).toString());
        body.remove("cost");

        mockMvc.perform(post("/api/admin/maintenance")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadMaintenanceImages_withPng_returns201AndUrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "photo.png", "image/png", PNG);

        mockMvc.perform(multipart("/api/admin/maintenance/images").file(file).header("Authorization", adminAuth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0]", startsWith("/uploads/maintenance/")));
    }

    @Test
    void uploadMaintenanceImages_withDisallowedType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "script.exe", "application/octet-stream", PNG);

        mockMvc.perform(multipart("/api/admin/maintenance/images").file(file).header("Authorization", adminAuth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadMaintenanceImages_withoutFiles_returns400() throws Exception {
        mockMvc.perform(multipart("/api/admin/maintenance/images").header("Authorization", adminAuth))
                .andExpect(status().isBadRequest());
    }

    // ── Products ──────────────────────────────────────────────────────────────

    @Test
    void getAllProductsForAdmin_includesArchivedProducts() throws Exception {
        Product active = product(10, true);
        Product archived = product(10, false);

        mockMvc.perform(get("/api/admin/products").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(active.getName())))
                .andExpect(jsonPath("$[*].name", hasItem(archived.getName())));
    }

    // ── Categories ────────────────────────────────────────────────────────────

    @Test
    void createCategory_withNewName_returns201() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Garden Furniture "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").isNumber())
                .andExpect(jsonPath("$.name").value("Garden Furniture"));
    }

    @Test
    void createCategory_withExistingName_returns422() throws Exception {
        ProductCategory existing = category();

        mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", existing.getName().toUpperCase()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createCategory_withBlankName_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", " "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCategory_withNewName_returnsRenamedCategory() throws Exception {
        ProductCategory category = category();

        mockMvc.perform(put("/api/admin/categories/{id}", category.getCategoryId())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Office Desks"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Office Desks"));
    }

    @Test
    void updateCategory_whenCategoryMissing_returns404() throws Exception {
        mockMvc.perform(put("/api/admin/categories/{id}", MISSING_ID)
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Office Desks"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteCategory_whenUnused_returns204() throws Exception {
        ProductCategory category = category();

        mockMvc.perform(delete("/api/admin/categories/{id}", category.getCategoryId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isNoContent());

        assertThat(categoryRepository.findById(category.getCategoryId())).isEmpty();
    }

    @Test
    void deleteCategory_whenUsedByProduct_returns422() throws Exception {
        Product product = product(5, true);

        mockMvc.perform(delete(
                                "/api/admin/categories/{id}",
                                product.getCategory().getCategoryId())
                        .header("Authorization", adminAuth))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deleteCategory_whenCategoryMissing_returns404() throws Exception {
        mockMvc.perform(delete("/api/admin/categories/{id}", MISSING_ID).header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
    }
}
