package com.gmc.backend.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gmc.backend.model.Product;
import com.gmc.backend.model.User;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CartControllerIT extends ShopIntegrationTestBase {

    private long firstCartItemId(User user) throws Exception {
        String body = mockMvc.perform(get("/api/cart").header("Authorization", bearer(user)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper
                .readTree(body)
                .path("items")
                .get(0)
                .path("cartItemId")
                .asLong();
    }

    @Test
    void getCart_forNewCustomer_returnsEmptyCart() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(get("/api/cart").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").isNumber())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalAmount").value(0));
    }

    @Test
    void getCart_asAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/cart").header("Authorization", bearer(createAdmin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void addItem_withStock_addsItemAndTotals() throws Exception {
        User customer = createCustomer();
        Product product = createProduct("2500.00", 10);

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", product.getProductId(), "quantity", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].product.productId").value(product.getProductId()))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.totalAmount").value(5000.00));
    }

    @Test
    void addItem_sameProductTwice_mergesQuantity() throws Exception {
        User customer = createCustomer();
        Product product = createProduct("100.00", 10);
        addToCart(customer, product, 1);

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", product.getProductId(), "quantity", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(4));
    }

    @Test
    void addItem_moreThanInStock_returns422() throws Exception {
        User customer = createCustomer();
        Product product = createProduct("100.00", 2);

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", product.getProductId(), "quantity", 3))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addItem_inactiveProduct_returns422() throws Exception {
        User customer = createCustomer();
        Product product = createProduct("100.00", 5);
        product.setIsActive(false);
        productRepository.save(product);

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", product.getProductId(), "quantity", 1))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addItem_unknownProduct_returns404() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", bearer(createCustomer()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", 999_999, "quantity", 1))))
                .andExpect(status().isNotFound());
    }

    @Test
    void addItem_withZeroQuantityOrMissingProduct_returns400() throws Exception {
        String token = bearer(createCustomer());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productId", 1, "quantity", 0))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("quantity", 1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateQuantity_withinStock_updatesItem() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("300.00", 10), 1);
        long itemId = firstCartItemId(customer);

        mockMvc.perform(put("/api/cart/items/{id}", itemId)
                        .param("quantity", "5")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.totalAmount").value(1500.00));
    }

    @Test
    void updateQuantity_toZero_removesItem() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("300.00", 10), 2);
        long itemId = firstCartItemId(customer);

        mockMvc.perform(put("/api/cart/items/{id}", itemId)
                        .param("quantity", "0")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void updateQuantity_aboveStock_returns422() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("300.00", 3), 1);
        long itemId = firstCartItemId(customer);

        mockMvc.perform(put("/api/cart/items/{id}", itemId)
                        .param("quantity", "4")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateQuantity_withoutQuantityParam_returns400() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("300.00", 3), 1);

        mockMvc.perform(put("/api/cart/items/{id}", firstCartItemId(customer))
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateQuantity_onAnotherCustomersItem_isRejectedAndItemUnchanged() throws Exception {
        User owner = createCustomer();
        User other = createCustomer();
        addToCart(owner, createProduct("300.00", 10), 1);
        long itemId = firstCartItemId(owner);

        mockMvc.perform(put("/api/cart/items/{id}", itemId)
                        .param("quantity", "9")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/cart").header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.items[0].quantity").value(1));
    }

    @Test
    void updateQuantity_unknownItem_returns404() throws Exception {
        mockMvc.perform(put("/api/cart/items/{id}", 999_999)
                        .param("quantity", "1")
                        .header("Authorization", bearer(createCustomer())))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeItem_ownItem_removesIt() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("300.00", 10), 1);
        addToCart(customer, createProduct("50.00", 10), 2);
        long itemId = firstCartItemId(customer);

        mockMvc.perform(delete("/api/cart/items/{id}", itemId).header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void removeItem_anotherCustomersItem_returns422() throws Exception {
        User owner = createCustomer();
        addToCart(owner, createProduct("300.00", 10), 1);
        long itemId = firstCartItemId(owner);

        mockMvc.perform(delete("/api/cart/items/{id}", itemId).header("Authorization", bearer(createCustomer())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void removeItem_unknownItem_returns404() throws Exception {
        mockMvc.perform(delete("/api/cart/items/{id}", 999_999).header("Authorization", bearer(createCustomer())))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearCart_emptiesCart() throws Exception {
        User customer = createCustomer();
        addToCart(customer, createProduct("300.00", 10), 1);
        addToCart(customer, createProduct("50.00", 10), 2);

        mockMvc.perform(delete("/api/cart").header("Authorization", bearer(customer)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cart").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void clearCart_withoutToken_isDenied() throws Exception {
        mockMvc.perform(delete("/api/cart")).andExpect(status().isForbidden());
    }
}
