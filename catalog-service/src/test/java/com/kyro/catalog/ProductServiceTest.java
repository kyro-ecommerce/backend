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

  @Test
  void reserveStockDeductsStockAndSavesIdempotencyRecord() {
    ProductVariantRepository variantRepo = org.mockito.Mockito.mock(ProductVariantRepository.class);
    ProcessedStockReservationRepository reservationRepo =
        org.mockito.Mockito.mock(ProcessedStockReservationRepository.class);
    ProductService service =
        new ProductService(null, null, null, null, null, null, variantRepo, reservationRepo);

    ProductVariant variant = new ProductVariant();
    variant.setId(100L);
    variant.setActive(true);
    variant.setStock(10);

    org.mockito.Mockito.when(reservationRepo.markReservedIfAbsent(1L)).thenReturn(1);
    org.mockito.Mockito.when(variantRepo.findByIdWithLock(100L))
        .thenReturn(java.util.Optional.of(variant));

    boolean result =
        service.reserveStock(1L, List.of(java.util.Map.of("variantId", 100L, "quantity", 3)));

    assertTrue(result);
    assertEquals(7, variant.getStock());
    org.mockito.Mockito.verify(reservationRepo).markReservedIfAbsent(1L);
  }

  @Test
  void reserveStockIsIdempotentOnDuplicateCall() {
    ProductVariantRepository variantRepo = org.mockito.Mockito.mock(ProductVariantRepository.class);
    ProcessedStockReservationRepository reservationRepo =
        org.mockito.Mockito.mock(ProcessedStockReservationRepository.class);
    ProductService service =
        new ProductService(null, null, null, null, null, null, variantRepo, reservationRepo);

    org.mockito.Mockito.when(reservationRepo.markReservedIfAbsent(1L)).thenReturn(0);

    boolean result =
        service.reserveStock(1L, List.of(java.util.Map.of("variantId", 100L, "quantity", 3)));

    assertTrue(result);
    org.mockito.Mockito.verify(variantRepo, org.mockito.Mockito.never())
        .findByIdWithLock(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void reserveStockFailsWhenStockInsufficient() {
    ProductVariantRepository variantRepo = org.mockito.Mockito.mock(ProductVariantRepository.class);
    ProcessedStockReservationRepository reservationRepo =
        org.mockito.Mockito.mock(ProcessedStockReservationRepository.class);
    ProductService service =
        new ProductService(null, null, null, null, null, null, variantRepo, reservationRepo);

    ProductVariant variant = new ProductVariant();
    variant.setId(100L);
    variant.setActive(true);
    variant.setStock(2);

    org.mockito.Mockito.when(reservationRepo.markReservedIfAbsent(1L)).thenReturn(1);
    org.mockito.Mockito.when(variantRepo.findByIdWithLock(100L))
        .thenReturn(java.util.Optional.of(variant));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reserveStock(1L, List.of(java.util.Map.of("variantId", 100L, "quantity", 5))));

    assertEquals(2, variant.getStock());
  }
}
