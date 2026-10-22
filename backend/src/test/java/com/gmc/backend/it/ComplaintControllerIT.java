package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Complaint;
import com.gmc.backend.model.Order;
import com.gmc.backend.model.OrderItem;
import com.gmc.backend.model.OrderStatus;
import com.gmc.backend.model.Product;
import com.gmc.backend.model.ProductCategory;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.ComplaintRepository;
import com.gmc.backend.repository.OrderRepository;
import com.gmc.backend.repository.ProductCategoryRepository;
import com.gmc.backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ComplaintControllerIT extends IntegrationTestBase {

    @Autowired
    private ComplaintRepository complaintRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    private Product saveProduct(String name) {
        ProductCategory category = categoryRepository.save(
                ProductCategory.builder().name("Category " + UUID.randomUUID()).build());
        return productRepository.save(Product.builder()
                .name(name)
                .price(new BigDecimal("25000.00"))
                .category(category)
                .stockQuantity(5)
                .build());
    }

    private Order saveOrder(User owner, Product product) {
        Order order = Order.builder()
                .orderDate(LocalDateTime.now())
                .status(OrderStatus.DELIVERED)
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

    private static Map<String, Object> complaint(String description, Long orderId, Long productId) {
        Map<String, Object> body = new HashMap<>();
        body.put("description", description);
        body.put("orderId", orderId);
        body.put("productId", productId);
        return body;
    }

    @Test
    void submit_withoutOrder_returns201Open() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(post("/api/complaints")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(complaint("  Delivery driver was rude  ", null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.description").value("Delivery driver was rude"))
                .andExpect(jsonPath("$.userId").value(customer.getUserId()));

        assertThat(complaintRepository.findByUser_UserId(customer.getUserId())).hasSize(1);
    }

    @Test
    void submit_aboutProductInOwnOrder_linksOrderAndProduct() throws Exception {
        User customer = createCustomer();
        Product product = saveProduct("Teak Chair IT");
        Order order = saveOrder(customer, product);

        mockMvc.perform(post("/api/complaints")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(complaint("Leg arrived cracked", order.getOrderId(), product.getProductId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(order.getOrderId()))
                .andExpect(jsonPath("$.productId").value(product.getProductId()))
                .andExpect(jsonPath("$.productName").value("Teak Chair IT"));
    }

    @Test
    void submit_aboutSomeoneElsesOrder_returns422() throws Exception {
        User owner = createCustomer();
        User intruder = createCustomer();
        Order order = saveOrder(owner, saveProduct("Cabinet IT"));

        mockMvc.perform(post("/api/complaints")
                        .header("Authorization", bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(complaint("Not my order", order.getOrderId(), null))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(complaintRepository.findByUser_UserId(intruder.getUserId())).isEmpty();
    }

    @Test
    void submit_withProductNotInOrder_returns422() throws Exception {
        User customer = createCustomer();
        Order order = saveOrder(customer, saveProduct("Desk IT"));
        Product unrelated = saveProduct("Shelf IT");

        mockMvc.perform(post("/api/complaints")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(complaint("Wrong item", order.getOrderId(), unrelated.getProductId()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void submit_withBlankDescription_returns400() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(post("/api/complaints")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(complaint("", null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getMyComplaints_returnsOnlyCallersComplaints() throws Exception {
        User customer = createCustomer();
        User other = createCustomer();
        complaintRepository.save(Complaint.builder()
                .description("Mine")
                .date(LocalDateTime.now())
                .status("OPEN")
                .user(customer)
                .build());
        complaintRepository.save(Complaint.builder()
                .description("Theirs")
                .date(LocalDateTime.now())
                .status("OPEN")
                .user(other)
                .build());

        mockMvc.perform(get("/api/complaints").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].description").value("Mine"));
    }

    @Test
    void complaints_asTechnician_returns403() throws Exception {
        User technician = createTechnician();

        mockMvc.perform(get("/api/complaints").header("Authorization", bearer(technician)))
                .andExpect(status().isForbidden());
    }
}
