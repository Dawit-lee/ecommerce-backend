package com.ecommerce.project;

import com.ecommerce.project.model.Product;
import com.ecommerce.project.model.User;
import com.ecommerce.project.repositories.ProductRepository;
import com.ecommerce.project.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@Transactional
@TestPropertySource(properties = {
        "spring.app.jwtSecret=dGVzdC1vbmx5LXNlY3JldC10ZXN0LW9ubHktc2VjcmV0LXRlc3Qtb25seS0wMTIzNDU2Nzg5",
        "stripe.secret.key=sk_test_dummy"
})
class ProductOwnershipTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    private static final String BODY = """
            {"productName":"Updated name","description":"Updated description","quantity":5,"price":100,"discount":10,"specialPrice":90}
            """;

    @Autowired WebApplicationContext context;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository userRepository;

    MockMvc mockMvc;
    Long seller1ProductId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // seller1 and admin are seeded by WebSecurityConfig; seller2 is a second seller.
        if (!userRepository.existsByUserName("seller2")) {
            userRepository.save(new User("seller2", "seller2@example.com", "password"));
        }
        Product product = new Product();
        product.setProductName("Seller1 product");
        product.setDescription("Owned by seller1");
        product.setQuantity(10);
        product.setPrice(100);
        product.setUser(userRepository.findByUserName("seller1").orElseThrow());
        seller1ProductId = productRepository.save(product).getProductId();
    }

    @Test
    void ownerCanUpdateOwnProduct() throws Exception {
        mockMvc.perform(put("/api/seller/products/{id}", seller1ProductId)
                        .with(user("seller1").roles("SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Updated name"));
    }

    @Test
    void otherSellerCannotUpdateProduct() throws Exception {
        mockMvc.perform(put("/api/seller/products/{id}", seller1ProductId)
                        .with(user("seller2").roles("SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(false));
        assertThat(productRepository.findById(seller1ProductId).orElseThrow().getProductName())
                .isEqualTo("Seller1 product");
    }

    @Test
    void otherSellerCannotDeleteProduct() throws Exception {
        mockMvc.perform(delete("/api/seller/products/{id}", seller1ProductId)
                        .with(user("seller2").roles("SELLER")))
                .andExpect(status().isForbidden());
        assertThat(productRepository.findById(seller1ProductId)).isPresent();
    }

    @Test
    void otherSellerCannotUpdateProductImage() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.png", "image/png", new byte[]{1});
        mockMvc.perform(multipart("/api/seller/products/{id}/image", seller1ProductId).file(image)
                        .with(req -> { req.setMethod("PUT"); return req; })
                        .with(user("seller2").roles("SELLER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanUpdateAndDeleteAnyProduct() throws Exception {
        mockMvc.perform(put("/api/seller/products/{id}", seller1ProductId)
                        .with(user("admin").roles("USER", "SELLER", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/products/{id}", seller1ProductId)
                        .with(user("admin").roles("USER", "SELLER", "ADMIN")))
                .andExpect(status().isOk());
        assertThat(productRepository.findById(seller1ProductId)).isEmpty();
    }
}
