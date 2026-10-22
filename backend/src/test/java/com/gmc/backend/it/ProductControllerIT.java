package com.gmc.backend.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.gmc.backend.repository.OrderRepository;
import com.gmc.backend.repository.ProductCategoryRepository;
import com.gmc.backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class ProductControllerIT extends IntegrationTestBase {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    private ProductCategory tables;
    private ProductCategory chairs;
    private Product teakTable;
    private Product oakChair;
    private Product archived;

    @BeforeEach
    void seedCatalogue() {
        String unique = UUID.randomUUID().toString().substring(0, 6);
        tables = categoryRepository.save(
                ProductCategory.builder().name("Tables " + unique).build());
        chairs = categoryRepository.save(
                ProductCategory.builder().name("Chairs " + unique).build());
        teakTable = saveProduct("Teak Dining Table " + unique, tables, "Teak", true);
        oakChair = saveProduct("Oak Chair " + unique, chairs, "Oak", true);
        archived = saveProduct("Old Teak Bench " + unique, tables, "Teak", false);
    }

    private Product saveProduct(String name, ProductCategory category, String material, boolean active) {
        return productRepository.save(Product.builder()
                .name(name)
                .description("Handmade " + name)
                .price(new BigDecimal("45000.00"))
                .category(category)
                .material(material)
                .stockQuantity(5)
                .isActive(active)
                .build());
    }

    private Map<String, Object> productBody(String name, Long categoryId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "CNC carved");
        body.put("price", 12500);
        body.put("categoryId", categoryId);
        body.put("material", "Mahogany");
        body.put("imageUrls", List.of("/uploads/products/a.png", "/uploads/products/b.png"));
        body.put("imageUrl", "/uploads/products/b.png");
        body.put("stockQuantity", 3);
        return body;
    }

    private String idFilter(Product product) {
        return "$[?(@.productId == " + product.getProductId() + ")]";
    }

    // ── Public catalogue ────────────────────────────────────

    @Test
    void getAll_anonymous_returnsOnlyActiveProducts() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idFilter(teakTable)).isNotEmpty())
                .andExpect(jsonPath(idFilter(oakChair)).isNotEmpty())
                .andExpect(jsonPath(idFilter(archived), empty()));
    }

    @Test
    void getAll_bySearch_matchesNameIgnoringCase() throws Exception {
        mockMvc.perform(get("/api/products").param("search", teakTable.getName().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].productId").value(teakTable.getProductId()));
    }

    @Test
    void getAll_byCategory_returnsActiveProductsInThatCategory() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("categoryId", tables.getCategoryId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[*].productId", contains(teakTable.getProductId().intValue())));
    }

    @Test
    void getAll_byUnknownCategory_returns404() throws Exception {
        mockMvc.perform(get("/api/products").param("categoryId", "999999")).andExpect(status().isNotFound());
    }

    @Test
    void getAll_byMaterial_filtersCaseInsensitively() throws Exception {
        mockMvc.perform(get("/api/products").param("material", "oak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idFilter(oakChair)).isNotEmpty())
                .andExpect(jsonPath(idFilter(teakTable), empty()));
    }

    @Test
    void getById_existingProduct_returnsDetails() throws Exception {
        mockMvc.perform(get("/api/products/{id}", teakTable.getProductId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(teakTable.getName()))
                .andExpect(jsonPath("$.category.categoryId").value(tables.getCategoryId()))
                .andExpect(jsonPath("$.material").value("Teak"))
                .andExpect(jsonPath("$.stockQuantity").value(5))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void getById_unknownProduct_returns404() throws Exception {
        mockMvc.perform(get("/api/products/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void getById_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get("/api/products/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void getReviews_withVisibleAndHiddenReviews_returnsOnlyVisibleWithAverage() throws Exception {
        User customer = createCustomer();
        Order order = Order.builder()
                .orderDate(LocalDateTime.now())
                .status(OrderStatus.DELIVERED)
                .totalAmount(new BigDecimal("45000.00"))
                .user(customer)
                .build();
        order.getItems()
                .add(OrderItem.builder()
                        .order(order)
                        .product(teakTable)
                        .quantity(1)
                        .unitPrice(new BigDecimal("45000.00"))
                        .build());
        order = orderRepository.save(order);
        User other = createCustomer();
        feedbackRepository.save(Feedback.builder()
                .rating(4)
                .comment("Solid build")
                .createdAt(LocalDateTime.now())
                .user(customer)
                .order(order)
                .product(teakTable)
                .build());
        feedbackRepository.save(Feedback.builder()
                .rating(2)
                .comment("Fine")
                .createdAt(LocalDateTime.now())
                .user(other)
                .order(order)
                .product(teakTable)
                .build());
        feedbackRepository.save(Feedback.builder()
                .rating(1)
                .comment("Spam spam")
                .createdAt(LocalDateTime.now())
                .user(createCustomer())
                .order(order)
                .product(teakTable)
                .hidden(true)
                .build());

        mockMvc.perform(get("/api/products/{id}/reviews", teakTable.getProductId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(2))
                .andExpect(jsonPath("$.averageRating").value(3.0))
                .andExpect(jsonPath("$.reviews[*].comment", containsInAnyOrder("Solid build", "Fine")))
                .andExpect(jsonPath("$.reviews[0].reviewerName").value(endsWith(".")))
                .andExpect(jsonPath("$.reviews[0].verifiedPurchase").value(true));
    }

    @Test
    void getReviews_productWithoutReviews_returnsZeroAverage() throws Exception {
        mockMvc.perform(get("/api/products/{id}/reviews", oakChair.getProductId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.averageRating").value(0))
                .andExpect(jsonPath("$.reviews", empty()));
    }

    @Test
    void getReviews_unknownProduct_returns404() throws Exception {
        mockMvc.perform(get("/api/products/{id}/reviews", 999999)).andExpect(status().isNotFound());
    }

    @Test
    void getAllCategories_anonymous_listsCategories() throws Exception {
        mockMvc.perform(get("/api/products/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.categoryId == " + tables.getCategoryId() + ")].name")
                        .value(tables.getName()))
                .andExpect(jsonPath("$[?(@.categoryId == " + chairs.getCategoryId() + ")].name")
                        .value(chairs.getName()));
    }

    // ── Admin management ────────────────────────────────────

    @Test
    void create_asAdmin_returns201WithCoverImage() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(post("/api/products")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productBody("Carved Wall Panel", chairs.getCategoryId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").isNumber())
                .andExpect(jsonPath("$.name").value("Carved Wall Panel"))
                .andExpect(jsonPath("$.imageUrl").value("/uploads/products/b.png"))
                .andExpect(jsonPath("$.imageUrls", hasSize(2)))
                .andExpect(jsonPath("$.stockQuantity").value(3))
                .andExpect(jsonPath("$.active").value(true));

        assertThat(productRepository.findByNameContainingIgnoreCase("Carved Wall Panel"))
                .hasSize(1);
    }

    @Test
    void create_withMissingNameAndZeroPrice_returns400() throws Exception {
        User admin = createAdmin();
        Map<String, Object> body = productBody("", chairs.getCategoryId());
        body.put("price", 0);

        mockMvc.perform(post("/api/products")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void create_withUnknownCategory_returns404() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(post("/api/products")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productBody("Lamp", 999999L))))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_asCustomer_returns403() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(post("/api/products")
                        .header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productBody("Lamp", chairs.getCategoryId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_anonymous_returns403() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productBody("Lamp", chairs.getCategoryId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadImages_asAdmin_storesImagesAndReturnsUrls() throws Exception {
        User admin = createAdmin();
        MockMultipartFile png = new MockMultipartFile("files", "front.png", "image/png", new byte[] {1, 2, 3});
        MockMultipartFile jpg = new MockMultipartFile("files", "side.JPG", "image/jpeg", new byte[] {4, 5, 6});

        mockMvc.perform(multipart("/api/products/images").file(png).file(jpg).header("Authorization", bearer(admin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]", startsWith("/uploads/products/")))
                .andExpect(jsonPath("$[1]").value(endsWith(".jpg")));
    }

    @Test
    void uploadImages_withDisallowedExtension_returns400() throws Exception {
        User admin = createAdmin();
        MockMultipartFile exe = new MockMultipartFile("files", "virus.exe", "application/octet-stream", new byte[] {1});

        mockMvc.perform(multipart("/api/products/images").file(exe).header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FILE"));
    }

    @Test
    void uploadImages_asCustomer_returns403() throws Exception {
        User customer = createCustomer();
        MockMultipartFile png = new MockMultipartFile("files", "front.png", "image/png", new byte[] {1});

        mockMvc.perform(multipart("/api/products/images").file(png).header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_asAdmin_changesFieldsAndCategory() throws Exception {
        User admin = createAdmin();
        Map<String, Object> body = productBody("Teak Dining Table XL", chairs.getCategoryId());
        body.put("price", 52000);

        mockMvc.perform(put("/api/products/{id}", teakTable.getProductId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Teak Dining Table XL"))
                .andExpect(jsonPath("$.price").value(52000))
                .andExpect(jsonPath("$.category.categoryId").value(chairs.getCategoryId()))
                .andExpect(jsonPath("$.stockQuantity").value(3));

        Product saved = productRepository.findById(teakTable.getProductId()).orElseThrow();
        assertThat(saved.getMaterial()).isEqualTo("Mahogany");
    }

    @Test
    void update_unknownProduct_returns404() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(put("/api/products/{id}", 999999)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productBody("Ghost", chairs.getCategoryId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_withNegativeStock_returns400() throws Exception {
        User admin = createAdmin();
        Map<String, Object> body = productBody("Teak Dining Table", tables.getCategoryId());
        body.put("stockQuantity", -1);

        mockMvc.perform(put("/api/products/{id}", teakTable.getProductId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_asTechnician_returns403() throws Exception {
        User technician = createTechnician();

        mockMvc.perform(put("/api/products/{id}", teakTable.getProductId())
                        .header("Authorization", bearer(technician))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productBody("Hacked", tables.getCategoryId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void setActive_asAdmin_restoresArchivedProduct() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(patch("/api/products/{id}/active", archived.getProductId())
                        .param("active", "true")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/products"))
                .andExpect(jsonPath(idFilter(archived)).isNotEmpty());
    }

    @Test
    void setActive_false_hidesProductFromShop() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(patch("/api/products/{id}/active", oakChair.getProductId())
                        .param("active", "false")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/products")).andExpect(jsonPath(idFilter(oakChair), empty()));
    }

    @Test
    void setActive_withoutActiveParam_returns400() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(patch("/api/products/{id}/active", oakChair.getProductId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setActive_asCustomer_returns403() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(patch("/api/products/{id}/active", oakChair.getProductId())
                        .param("active", "false")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_asAdmin_archivesProductButKeepsIt() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(delete("/api/products/{id}", teakTable.getProductId()).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        Product saved = productRepository.findById(teakTable.getProductId()).orElseThrow();
        assertThat(saved.getIsActive()).isFalse();
        mockMvc.perform(get("/api/products/{id}", teakTable.getProductId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void delete_unknownProduct_returns404() throws Exception {
        User admin = createAdmin();

        mockMvc.perform(delete("/api/products/{id}", 999999).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asCustomer_returns403() throws Exception {
        User customer = createCustomer();

        mockMvc.perform(delete("/api/products/{id}", teakTable.getProductId())
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());

        assertThat(productRepository
                        .findById(teakTable.getProductId())
                        .orElseThrow()
                        .getIsActive())
                .isTrue();
    }
}
