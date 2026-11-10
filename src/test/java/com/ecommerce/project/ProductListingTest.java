package com.ecommerce.project;

import com.ecommerce.project.model.Product;
import com.ecommerce.project.payload.ProductResponse;
import com.ecommerce.project.repositories.ProductRepository;
import com.ecommerce.project.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

// getAllProducts fetches a Slice and takes the total from a cache; the response must still be a correct page.
@SpringBootTest
@Testcontainers
@Transactional
@TestPropertySource(properties = {
        "spring.app.jwtSecret=dGVzdC1vbmx5LXNlY3JldC10ZXN0LW9ubHktc2VjcmV0LXRlc3Qtb25seS0wMTIzNDU2Nzg5",
        "stripe.secret.key=sk_test_dummy"
})
class ProductListingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;

    @Test
    void pageMetadataAndKeywordFilterStayCorrect() {
        for (String name : new String[]{"Red Keyboard", "Blue Keyboard", "Green Keyboard", "Plain Mouse"}) {
            Product p = new Product();
            p.setProductName(name);
            p.setDescription("listing test");
            p.setQuantity(1);
            p.setPrice(100);
            productRepository.save(p);
        }

        ProductResponse firstPage = productService.getAllProducts(0, 2, "productId", "asc", "keyboard", null);
        assertThat(firstPage.getContent()).extracting("productName").containsExactly("Red Keyboard", "Blue Keyboard");
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isLastPage()).isFalse();

        // second call hits the cached count
        ProductResponse lastPage = productService.getAllProducts(1, 2, "productId", "asc", "KEYBOARD", null);
        assertThat(lastPage.getContent()).extracting("productName").containsExactly("Green Keyboard");
        assertThat(lastPage.getTotalElements()).isEqualTo(3);
        assertThat(lastPage.isLastPage()).isTrue();
    }
}
