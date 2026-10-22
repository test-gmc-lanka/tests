package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Product;
import com.gmc.backend.model.User;
import com.gmc.backend.service.StripeGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class OrderControllerIT extends ShopIntegrationTestBase {

    private static long minorUnits(JsonNode order) {
        return new BigDecimal(order.path("totalAmount").asText())
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private JsonNode placeCardOrder(User customer) throws Exception {
        enableCardPayments();
        return placeOrder(customer, createProduct("1000.00", 5), 1, "CARD");
    }

    // ── GET /api/orders/checkout-summary ──

    @Test
    void checkoutSummary_withCartItems_returnsPriceBreakdown() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("2500.00", 10), 2);

        mockMvc.perform(get("/api/orders/checkout-summary").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.subtotal").value(5000.00))
                .andExpect(jsonPath("$.totalAmount").isNumber())
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.cardPaymentsEnabled").value(false))
                .andExpect(jsonPath("$.stripePublishableKey").doesNotExist());
    }

    @Test
    void checkoutSummary_withStripeConfigured_offersCardPayment() throws Exception {
        enableCardPayments();

        mockMvc.perform(get("/api/orders/checkout-summary").header("Authorization", bearer(createCustomer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(0))
                .andExpect(jsonPath("$.cardPaymentsEnabled").value(true))
                .andExpect(jsonPath("$.stripePublishableKey").value(PUBLISHABLE_KEY));
    }

    @Test
    void checkoutSummary_asTechnician_returns403() throws Exception {
        mockMvc.perform(get("/api/orders/checkout-summary").header("Authorization", bearer(createTechnician())))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/orders ──

    @Test
    void placeOrder_cashOnDelivery_createsOrderReservesStockAndEmptiesCart() throws Exception {
        User customer = createCustomer();
        Product product = createProduct("2500.00", 10);

        JsonNode order = placeOrder(customer, product, 3, "CASH_ON_DELIVERY");

        assertThat(order.path("orderId").asLong()).isPositive();
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        assertThat(order.path("paymentMethod").asText()).isEqualTo("CASH_ON_DELIVERY");
        assertThat(order.path("paymentStatus").asText()).isEqualTo("PENDING");
        assertThat(order.path("shippingCity").asText()).isEqualTo("Colombo");
        assertThat(order.path("items").get(0).path("quantity").asInt()).isEqualTo(3);
        assertThat(productRepository
                        .findById(product.getProductId())
                        .orElseThrow()
                        .getStockQuantity())
                .isEqualTo(7);
        mockMvc.perform(get("/api/cart").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.items").isEmpty());
        verify(emailService).send(eq(customer.getEmail()), anyString(), anyString());
    }

    @Test
    void placeOrder_byCard_createsUnpaidOrderWithoutCharging() throws Exception {
        User customer = createCustomer();

        JsonNode order = placeCardOrder(customer);

        assertThat(order.path("paymentMethod").asText()).isEqualTo("CARD");
        assertThat(order.path("paymentStatus").asText()).isEqualTo("PENDING");
        verify(stripeGateway, never()).createPaymentIntent(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void placeOrder_withEmptyCart_returns422() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(createCustomer()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest("CASH_ON_DELIVERY"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Cart is empty"));
    }

    @Test
    void placeOrder_byCardWhenStripeNotConfigured_returns422() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("1000.00", 5), 1);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest("CARD"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void placeOrder_whenStockRanOutAfterAddingToCart_returns422AndKeepsCart() throws Exception {
        User customer = createCustomer();
        Product product = createProduct("1000.00", 5);
        addToCart(customer, product, 3);
        product.setStockQuantity(1);
        productRepository.save(product);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest("CASH_ON_DELIVERY"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void placeOrder_withInvalidPhoneOrMissingPaymentMethod_returns400() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("1000.00", 5), 1);
        Map<String, Object> badPhone = orderRequest("CASH_ON_DELIVERY");
        badPhone.put("shippingPhone", "abc");
        Map<String, Object> noMethod = orderRequest("CASH_ON_DELIVERY");
        noMethod.remove("paymentMethod");

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(badPhone)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(noMethod)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void placeOrder_withoutToken_isDenied() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest("CASH_ON_DELIVERY"))))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/orders and /api/orders/{id} ──

    @Test
    void getMyOrders_returnsOnlyTheCustomersOwnOrders() throws Exception {
        User customer = createCustomer();
        User other = createCustomer();
        JsonNode mine = placeOrder(customer, createProduct("500.00", 10), 1, "CASH_ON_DELIVERY");
        placeOrder(other, createProduct("700.00", 10), 1, "CASH_ON_DELIVERY");

        mockMvc.perform(get("/api/orders").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(mine.path("orderId").asLong()))
                .andExpect(jsonPath("$[0].userId").value(customer.getUserId()));
    }

    @Test
    void getOrder_ownOrder_returnsDetails() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeOrder(customer, createProduct("500.00", 10), 2, "CASH_ON_DELIVERY");

        mockMvc.perform(get("/api/orders/{id}", order.path("orderId").asLong())
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.path("orderId").asLong()))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.shippingName").value("Nimal Perera"));
    }

    @Test
    void getOrder_anotherCustomersOrder_returns404() throws Exception {
        JsonNode order = placeOrder(createCustomer(), createProduct("500.00", 10), 1, "CASH_ON_DELIVERY");

        mockMvc.perform(get("/api/orders/{id}", order.path("orderId").asLong())
                        .header("Authorization", bearer(createCustomer())))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOrder_unknownOrNonNumericId_returns404Or400() throws Exception {
        String token = bearer(createCustomer());

        mockMvc.perform(get("/api/orders/{id}", 999_999).header("Authorization", token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/orders/abc").header("Authorization", token)).andExpect(status().isBadRequest());
    }

    // ── POST /api/orders/{id}/payment ──

    @Test
    void startPayment_forUnpaidCardOrder_returnsCardFormDetails() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeCardOrder(customer);
        stubNewIntent("pi_it_start", "pi_it_start_secret");

        mockMvc.perform(post("/api/orders/{id}/payment", order.path("orderId").asLong())
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.path("orderId").asLong()))
                .andExpect(jsonPath("$.clientSecret").value("pi_it_start_secret"))
                .andExpect(jsonPath("$.publishableKey").value(PUBLISHABLE_KEY))
                .andExpect(jsonPath("$.alreadyPaid").value(false));

        verify(stripeGateway)
                .createPaymentIntent(
                        eq(order.path("orderId").asLong()),
                        eq(minorUnits(order)),
                        eq(customer.getEmail()),
                        anyString());
    }

    @Test
    void startPayment_retried_reusesOpenPaymentInsteadOfCreatingAnother() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeCardOrder(customer);
        long orderId = order.path("orderId").asLong();
        stubNewIntent("pi_it_retry", "secret_retry");
        mockMvc.perform(post("/api/orders/{id}/payment", orderId).header("Authorization", bearer(customer)));
        when(stripeGateway.retrievePaymentIntent("pi_it_retry"))
                .thenReturn(new StripeGateway.Intent(
                        "pi_it_retry", "requires_payment_method", minorUnits(order), "lkr", "secret_retry"));

        mockMvc.perform(post("/api/orders/{id}/payment", orderId).header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("secret_retry"));

        verify(stripeGateway).createPaymentIntent(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void startPayment_whenAlreadyPaidAtStripe_marksOrderPaid() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeCardOrder(customer);
        long orderId = order.path("orderId").asLong();
        stubNewIntent("pi_it_paid", "secret_paid");
        mockMvc.perform(post("/api/orders/{id}/payment", orderId).header("Authorization", bearer(customer)));
        when(stripeGateway.retrievePaymentIntent("pi_it_paid"))
                .thenReturn(
                        new StripeGateway.Intent("pi_it_paid", "succeeded", minorUnits(order), "lkr", "secret_paid"));

        mockMvc.perform(post("/api/orders/{id}/payment", orderId).header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyPaid").value(true));

        mockMvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"));
    }

    @Test
    void startPayment_forCashOnDeliveryOrder_returns422() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeOrder(customer, createProduct("500.00", 10), 1, "CASH_ON_DELIVERY");

        mockMvc.perform(post("/api/orders/{id}/payment", order.path("orderId").asLong())
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This order is paid on delivery"));
    }

    @Test
    void startPayment_forAnotherCustomersOrder_returns404() throws Exception {
        JsonNode order = placeCardOrder(createCustomer());

        mockMvc.perform(post("/api/orders/{id}/payment", order.path("orderId").asLong())
                        .header("Authorization", bearer(createCustomer())))
                .andExpect(status().isNotFound());
        verify(stripeGateway, never()).createPaymentIntent(anyLong(), anyLong(), anyString(), anyString());
    }

    // ── POST /api/orders/{id}/payment/confirm ──

    @Test
    void confirmPayment_whenStripeReportsSuccess_marksOrderPaid() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeCardOrder(customer);
        long orderId = order.path("orderId").asLong();
        stubNewIntent("pi_it_confirm", "secret_confirm");
        mockMvc.perform(post("/api/orders/{id}/payment", orderId).header("Authorization", bearer(customer)));
        when(stripeGateway.retrievePaymentIntent("pi_it_confirm"))
                .thenReturn(new StripeGateway.Intent(
                        "pi_it_confirm", "succeeded", minorUnits(order), "lkr", "secret_confirm"));

        mockMvc.perform(post("/api/orders/{id}/payment/confirm", orderId).header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
    }

    @Test
    void confirmPayment_whenAmountDoesNotMatch_refundsInsteadOfMarkingPaid() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeCardOrder(customer);
        long orderId = order.path("orderId").asLong();
        stubNewIntent("pi_it_short", "secret_short");
        mockMvc.perform(post("/api/orders/{id}/payment", orderId).header("Authorization", bearer(customer)));
        when(stripeGateway.retrievePaymentIntent("pi_it_short"))
                .thenReturn(new StripeGateway.Intent("pi_it_short", "succeeded", 100, "lkr", "secret_short"));
        when(stripeGateway.refundPaymentIntent("pi_it_short")).thenReturn(true);

        mockMvc.perform(post("/api/orders/{id}/payment/confirm", orderId).header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
    }

    @Test
    void confirmPayment_whenCustomerHasNotPaidYet_leavesOrderPending() throws Exception {
        User customer = createCustomer();
        JsonNode order = placeCardOrder(customer);
        long orderId = order.path("orderId").asLong();
        stubNewIntent("pi_it_wait", "secret_wait");
        mockMvc.perform(post("/api/orders/{id}/payment", orderId).header("Authorization", bearer(customer)));
        when(stripeGateway.retrievePaymentIntent("pi_it_wait"))
                .thenReturn(new StripeGateway.Intent(
                        "pi_it_wait", "requires_payment_method", minorUnits(order), "lkr", "secret_wait"));

        mockMvc.perform(post("/api/orders/{id}/payment/confirm", orderId).header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    void confirmPayment_forAnotherCustomersOrder_returns404() throws Exception {
        JsonNode order = placeCardOrder(createCustomer());

        mockMvc.perform(post(
                                "/api/orders/{id}/payment/confirm",
                                order.path("orderId").asLong())
                        .header("Authorization", bearer(createCustomer())))
                .andExpect(status().isNotFound());
    }
}
