package com.kyro.catalog.dto;

import com.kyro.catalog.*;
import java.time.LocalDateTime;
import java.util.List;

public record ProductDTO(
    Long id,
    String title,
    String description,
    String detailedReview,
    String brand,
    int discountPercent,
    long minPrice,
    long minSalePrice,
    int totalStock,
    long quantitySold,
    List<VariantDTO> variants,
    List<AttributeDTO> attributes,
    List<ImageDTO> imageUrls,
    double averageRating,
    long numRatings,
    String topLevelCategory,
    String secondLevelCategory,
    LocalDateTime createdAt) {

  public ProductDTO(Product product) {
    this(
        product.getId(),
        product.getTitle(),
        product.getDescription(),
        product.getDetailedReview(),
        product.getBrand(),
        product.getDiscountPercent(),
        minPrice(product),
        Pricing.salePrice(minPrice(product), product.getDiscountPercent()),
        product.getTotalStock(),
        product.getQuantitySold(),
        product.getVariants().stream()
            .map(v -> new VariantDTO(v, product.getDiscountPercent()))
            .toList(),
        product.getAttributes().stream().map(AttributeDTO::new).toList(),
        product.getImages().stream().map(ImageDTO::new).toList(),
        Math.round(product.getAverageRating() * 10) / 10.0,
        product.getNumRatings(),
        topCategory(product),
        secondCategory(product),
        product.getCreatedAt());
  }

  private static long minPrice(Product product) {
    return product.getVariants().stream()
        .filter(ProductVariant::isActive)
        .mapToLong(ProductVariant::getPrice)
        .min()
        .orElse(0);
  }

  private static String topCategory(Product product) {
    Category c = product.getCategory();
    return c == null
        ? null
        : c.getParentCategory() == null ? c.getName() : c.getParentCategory().getName();
  }

  private static String secondCategory(Product product) {
    Category c = product.getCategory();
    return c != null && c.getParentCategory() != null ? c.getName() : null;
  }

  public record VariantDTO(
      Long id,
      String sku,
      String variantName,
      long price,
      long salePrice,
      int stock,
      boolean active,
      long stockVersion) {
    VariantDTO(ProductVariant v, int discount) {
      this(
          v.getId(),
          v.getSku(),
          v.getVariantName(),
          v.getPrice(),
          Pricing.salePrice(v.getPrice(), discount),
          v.getStock(),
          v.isActive(),
          v.getStockVersion());
    }
  }

  public record AttributeDTO(Long id, String name, String value, String unit) {
    AttributeDTO(ProductAttribute a) {
      this(a.getId(), a.getName(), a.getValue(), a.getUnit());
    }
  }
}
