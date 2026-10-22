package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Feedback;
import com.gmc.backend.model.Order;
import com.gmc.backend.model.OrderItem;
import com.gmc.backend.model.OrderStatus;
import com.gmc.backend.model.Product;
import com.gmc.backend.model.ProductCategory;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.FeedbackRepository;
import com.gmc.backend.repository.NotificationRepository;
import com.gmc.backend.repository.OrderRepository;
import com.gmc.backend.repository.ProductCategoryRepository;
import com.gmc.backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class FeedbackControllerIT extends IntegrationTestBase {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private User customer;
    private Product table;
    private Product chair;
    private Order deliveredOrder;

    @BeforeEach
    void seedDeliveredOrder() {
        customer = createCustomer();
        ProductCategory category = categoryRepository.save(ProductCategory.builder()
                .name("Furniture " + UUID.randomUUID().toString().substring(0, 6))
                .build());
        table = productRepository.save(Product.builder()
                .name("Teak Table")
                .price(new BigDecimal("40000.00"))
                .category(category)
                .stockQuantity(4)
                .build());
        chair = productRepository.save(Product.builder()
                .name("Oak Chair")
                .price(new BigDecimal("9000.00"))
                .category(category)
                .stockQuantity(4)
                .build());
        deliveredOrder = saveOrder(customer, OrderStatus.DELIVERED, table);
    }

    private Order saveOrder(User owner, OrderStatus status, Product product) {
        Order order = Order.builder()
                .orderDate(LocalDateTime.now())
                .status(status)
                .totalAmount(product.getPrice())
                .user(owner)
                .build();
        order.getItems()
                .add(OrderItem.builder()
                        .order(order)
                        .product(product)
                        .quantity(1)
                        .unitPrice(product.getPrice())
                        .build());
        return orderRepository.save(order);
    }

    private Map<String, Object> review(Long orderId, Long productId, int rating, String comment) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("productId", productId);
        body.put("rating", rating);
        body.put("comment", comment);
        return body;
    }

    private Feedback saveFeedback(User author, int rating, String comment) {
        return feedbackRepository.save(Feedback.builder()
                .rating(rating)
                .comment(comment)
                .createdAt(LocalDateTime.now())
                .user(author)
                .order(deliveredOrder)
                .product(table)
                .build());
    }

    // ── POST /api/feedback ──────────────────────────────────

    @Test
    void submit_forDeliveredProduct_returns201() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(deliveredOrder.getOrderId(), table.getProductId(), 5, "  Lovely!  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedbackId").isNumber())
                .andExpect(jsonPath("$.orderId").value(deliveredOrder.getOrderId()))
                .andExpect(jsonPath("$.productId").value(table.getProductId()))
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Lovely!"))
                .andExpect(jsonPath("$.hidden").value(false));
    }

    @Test
    void submit_lowRating_notifiesAdmins() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(deliveredOrder.getOrderId(), table.getProductId(), 1, "Cracked"))))
                .andExpect(status().isCreated());

        assertThat(notificationRepository.countByUserAndIsReadFalse(admin)).isEqualTo(1);
    }

    @Test
    void submit_twiceForSameProduct_returns422() throws Exception {
        String body = json(review(deliveredOrder.getOrderId(), table.getProductId(), 4, "Good"));
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void submit_forUndeliveredOrder_returns422() throws Exception {
        Order pending = saveOrder(customer, OrderStatus.PENDING, table);

        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(pending.getOrderId(), table.getProductId(), 4, "Early"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void submit_forProductNotInOrder_returns422() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(deliveredOrder.getOrderId(), chair.getProductId(), 4, "Nice"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void submit_forSomeoneElsesOrder_returns422() throws Exception {
        User stranger = createCustomer();

        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(deliveredOrder.getOrderId(), table.getProductId(), 1, "Fake"))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(feedbackRepository.findByUser_UserId(stranger.getUserId())).isEmpty();
    }

    @Test
    void submit_forUnknownOrder_returns404() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(999999L, table.getProductId(), 4, "Hmm"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void submit_withRatingOutOfRangeAndBlankComment_returns400() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(deliveredOrder.getOrderId(), table.getProductId(), 6, " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void submit_asAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", bearer(createAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(deliveredOrder.getOrderId(), table.getProductId(), 5, "Great"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void submit_anonymous_returns403() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(review(deliveredOrder.getOrderId(), table.getProductId(), 5, "Great"))))
                .andExpect(status().isForbidden());
    }

    // ── PUT /api/feedback/{id} ──────────────────────────────

    @Test
    void update_ownReview_changesRatingAndMarksEdited() throws Exception {
        Feedback feedback = saveFeedback(customer, 3, "Okay");

        mockMvc.perform(put("/api/feedback/{id}", feedback.getFeedbackId())
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rating", 5, "comment", "Grew on me"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Grew on me"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void update_unchangedReview_keepsUpdatedAtEmpty() throws Exception {
        Feedback feedback = saveFeedback(customer, 4, "Good");

        mockMvc.perform(put("/api/feedback/{id}", feedback.getFeedbackId())
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rating", 4, "comment", "Good"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    void update_someoneElsesReview_returns422() throws Exception {
        Feedback feedback = saveFeedback(customer, 4, "Good");
        User stranger = createCustomer();

        mockMvc.perform(put("/api/feedback/{id}", feedback.getFeedbackId())
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rating", 1, "comment", "Vandalised"))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(feedbackRepository
                        .findById(feedback.getFeedbackId())
                        .orElseThrow()
                        .getRating())
                .isEqualTo(4);
    }

    @Test
    void update_unknownReview_returns404() throws Exception {
        mockMvc.perform(put("/api/feedback/{id}", 999999)
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rating", 4, "comment", "Hello"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_withRatingZero_returns400() throws Exception {
        Feedback feedback = saveFeedback(customer, 4, "Good");

        mockMvc.perform(put("/api/feedback/{id}", feedback.getFeedbackId())
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rating", 0, "comment", "Zero"))))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/feedback ───────────────────────────────────

    @Test
    void getMyFeedback_returnsOnlyCallersReviews() throws Exception {
        saveFeedback(customer, 5, "Mine");
        saveFeedback(createCustomer(), 2, "Not mine");

        mockMvc.perform(get("/api/feedback").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].comment").value("Mine"))
                .andExpect(jsonPath("$[0].productName").value("Teak Table"))
                .andExpect(jsonPath("$[0].userId").value(customer.getUserId()));
    }

    @Test
    void getMyFeedback_asTechnician_returns403() throws Exception {
        mockMvc.perform(get("/api/feedback").header("Authorization", bearer(createTechnician())))
                .andExpect(status().isForbidden());
    }
}
