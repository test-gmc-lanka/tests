package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Order;
import com.gmc.backend.model.PaymentStatus;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.OrderRepository;
import com.gmc.backend.service.StripeGateway;
import java.math.RoundingMode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class PaymentControllerIT extends ShopIntegrationTestBase {

    private static final String WEBHOOK = "/api/payments/stripe/webhook";
    private static final String PAYLOAD = "{\"type\":\"payment_intent.succeeded\"}";
    private static final String SIGNATURE = "t=1,v1=abc";

    @Autowired
    private OrderRepository orderRepository;

    /** A card order whose Stripe payment has been created (as after the customer opened the card form). */
    private Order cardOrderWithIntent(String intentId) throws Exception {
        User customer = createCustomer();
        enableCardPayments();
        JsonNode order = placeOrder(customer, createProduct("1000.00", 5), 1, "CARD");
        stubNewIntent(intentId, intentId + "_secret");
        mockMvc.perform(post("/api/orders/{id}/payment", order.path("orderId").asLong())
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk());
        return orderRepository.findById(order.path("orderId").asLong()).orElseThrow();
    }

    private static long minorUnits(Order order) {
        return order.getTotalAmount()
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    @Test
    void webhook_paymentSucceeded_marksMatchingOrderPaidWithoutLogin() throws Exception {
        Order order = cardOrderWithIntent("pi_it_hook");
        when(stripeGateway.paymentIntentIdFromWebhook(PAYLOAD, SIGNATURE)).thenReturn(Optional.of("pi_it_hook"));
        when(stripeGateway.retrievePaymentIntent("pi_it_hook"))
                .thenReturn(new StripeGateway.Intent(
                        "pi_it_hook", "succeeded", minorUnits(order), "lkr", "pi_it_hook_secret"));

        mockMvc.perform(post(WEBHOOK)
                        .header("Stripe-Signature", SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(content().string("received"));

        Order updated = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(updated.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(updated.getPaidAt()).isNotNull();
    }

    @Test
    void webhook_paymentCanceled_marksOrderPaymentFailed() throws Exception {
        Order order = cardOrderWithIntent("pi_it_cancel");
        when(stripeGateway.paymentIntentIdFromWebhook(PAYLOAD, SIGNATURE)).thenReturn(Optional.of("pi_it_cancel"));
        when(stripeGateway.retrievePaymentIntent("pi_it_cancel"))
                .thenReturn(new StripeGateway.Intent(
                        "pi_it_cancel", "canceled", minorUnits(order), "lkr", "pi_it_cancel_secret"));

        mockMvc.perform(post(WEBHOOK).header("Stripe-Signature", SIGNATURE).content(PAYLOAD))
                .andExpect(status().isOk());

        assertThat(orderRepository.findById(order.getOrderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void webhook_forUnrelatedEventOrUnknownPayment_returns200AndChangesNothing() throws Exception {
        when(stripeGateway.paymentIntentIdFromWebhook(anyString(), anyString()))
                .thenReturn(Optional.empty(), Optional.of("pi_nobody_knows"));

        mockMvc.perform(post(WEBHOOK).header("Stripe-Signature", SIGNATURE).content("{\"type\":\"customer.created\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(WEBHOOK).header("Stripe-Signature", SIGNATURE).content(PAYLOAD))
                .andExpect(status().isOk());

        verify(stripeGateway, never()).retrievePaymentIntent(anyString());
    }

    @Test
    void webhook_withoutSignatureHeader_returns400() throws Exception {
        mockMvc.perform(post(WEBHOOK).content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Missing Stripe-Signature header"));

        verify(stripeGateway, never()).paymentIntentIdFromWebhook(anyString(), anyString());
    }

    @Test
    void webhook_withInvalidSignature_returns400() throws Exception {
        when(stripeGateway.paymentIntentIdFromWebhook(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("bad signature"));

        mockMvc.perform(post(WEBHOOK)
                        .header("Stripe-Signature", "t=1,v1=forged")
                        .content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid signature"));
    }

    @Test
    void webhook_whenWebhookSecretNotConfigured_returns503() throws Exception {
        when(stripeGateway.paymentIntentIdFromWebhook(anyString(), anyString()))
                .thenThrow(new IllegalStateException("stripe.webhook-secret is not set"));

        mockMvc.perform(post(WEBHOOK).header("Stripe-Signature", SIGNATURE).content(PAYLOAD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("Webhook not configured"));
    }

    @Test
    void otherPaymentEndpoints_requireCustomerLogin() throws Exception {
        // Only the webhook is public; anything else under /api/payments needs a CUSTOMER token
        mockMvc.perform(post("/api/payments/anything")).andExpect(status().isForbidden());
    }
}
