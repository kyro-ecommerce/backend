package com.kyro.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.kyro.catalog.client.OrderClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class ProductServiceTest {
  @Test
  void productPageableMapsPriceToDerivedMinPrice() {
    Pageable p = ProductService.productPageable(0, 20, List.of("price,asc"), false);
    assertEquals(Sort.Direction.ASC, p.getSort().getOrderFor("minPrice").getDirection());
    assertNotNull(p.getSort().getOrderFor("id"));
  }

  @Test
  void rejectsAdminStockSortForCustomer() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProductService.productPageable(0, 20, List.of("quantity,asc"), false));
  }

  @Test
  void adminCanSortByQuantitySold() {
    Pageable p = ProductService.productPageable(0, 20, List.of("quantitySold,desc"), true);
    assertEquals(Sort.Direction.DESC, p.getSort().getOrderFor("quantitySold").getDirection());
  }

  @Test
  void topSellingRemainsBatchOrdered() {
    Product p = new Product();
    p.setId(1L);
    p.setTitle("Phone");
    p.setBrand("Kyro");
    var result =
        ProductService.mapTopSellingProducts(
            List.of(new OrderClient.TopSellingProductResponse(1L, 7L)), List.of(p));
    assertEquals(7L, result.getFirst().get("quantitySold"));
  }
}
