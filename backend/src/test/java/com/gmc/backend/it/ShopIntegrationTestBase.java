package com.gmc.backend.it;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Product;
import com.gmc.backend.model.ProductCategory;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.ProductCategoryRepository;
import com.gmc.backend.repository.ProductRepository;
import com.gmc.backend.service.StripeGateway;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** Shared seed helpers for the cart, order and payment API tests. */
abstract class ShopIntegrationTestBase extends IntegrationTestBase {

    protected static final String PUBLISHABLE_KEY = "pk_test_it";

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected ProductCategoryRepository categoryRepository;

    /** The real gateway always has a currency (stripe.currency defaults to lkr), even with no keys. */
    @BeforeEach
    void defaultCurrency() {
        when(stripeGateway.currency()).thenReturn("lkr");
    }

    protected Product createProduct(String price, int stock) {
        ProductCategory category = new ProductCategory();
        category.setName("Category " + UUID.randomUUID().toString().substring(0, 8));
        category = categoryRepository.save(category);
        return productRepository.save(Product.builder()
                .name("Teak Chair " + UUID.randomUUID().toString().substring(0, 6))
                .description("Hand-finished teak chair")
                .price(new BigDecimal(price))
                .category(category)
                .stockQuantity(stock)
                .build());
    }

    /** Card payments switched on, as when stripe.secret-key and publishable-key are set. */
    protected void enableCardPayments() {
        when(stripeGateway.isConfigured()).thenReturn(true);
        when(stripeGateway.publishableKey()).thenReturn(PUBLISHABLE_KEY);
        when(stripeGateway.currency()).thenReturn("lkr");
    }

    protected void stubNewIntent(String intentId, String clientSecret) {
        when(stripeGateway.createPaymentIntent(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(new StripeGateway.Intent(intentId, "requires_payment_method", 0, "lkr", clientSecret));
    }

    protected void addToCart(User user, Product product, int quantity) throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", product.getProductId(), "quantity", quantity))))
                .andExpect(status().isOk());
    }

    protected Map<String, Object> orderRequest(String paymentMethod) {
        Map<String, Object> body = new HashMap<>();
        body.put("shippingName", "Nimal Perera");
        body.put("shippingPhone", "077 123 4567");
        body.put("shippingAddress", "12 Main Street");
        body.put("shippingCity", "Colombo");
        body.put("shippingPostalCode", "00100");
        body.put("paymentMethod", paymentMethod);
        return body;
    }

    /** Puts one product in the user's cart and checks out; returns the new order's JSON. */
    protected JsonNode placeOrder(User user, Product product, int quantity, String paymentMethod) throws Exception {
        addToCart(user, product, quantity);
        String response = mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(paymentMethod))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("order");
    }
}
