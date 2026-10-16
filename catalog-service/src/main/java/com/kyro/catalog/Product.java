package com.kyro.catalog;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Formula;

@Entity
public class Product {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @NotBlank
  @Size(max = 100)
  @Column(nullable = false, length = 100)
  private String title;

  @Size(max = 500)
  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "detailed_review", columnDefinition = "TEXT")
  private String detailedReview;

  @NotBlank
  @Size(max = 50)
  @Column(nullable = false, length = 50)
  private String brand;

  @Min(0)
  @Max(100)
  @Column(name = "discount_percent", nullable = false)
  private int discountPercent;

  @Column(name = "quantity_sold", nullable = false)
  private long quantitySold;

  @ManyToOne
  @JoinColumn(name = "category_id", nullable = false)
  private Category category;

  @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ProductVariant> variants = new ArrayList<>();

  @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ProductAttribute> attributes = new ArrayList<>();

  @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Image> images = new ArrayList<>();

  @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Review> reviews = new ArrayList<>();

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @Formula(
      "(select coalesce(min(v.price),0) from product_variant v where v.product_id=id and"
          + " v.active=true)")
  private long minPrice;

  @Formula(
      "(select coalesce(sum(v.stock),0) from product_variant v where v.product_id=id and"
          + " v.active=true)")
  private int totalStock;

  @Formula("(select count(*) from product_variant v where v.product_id=id and v.active=true)")
  private int activeVariantCount;

  @Formula("(select coalesce(avg(r.rating),0) from review r where r.product_id=id)")
  private double averageRating;

  @Formula("(select count(*) from review r where r.product_id=id)")
  private long numRatings;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String value) {
    title = value;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String value) {
    description = value;
  }

  public String getDetailedReview() {
    return detailedReview;
  }

  public void setDetailedReview(String value) {
    detailedReview = value;
  }

  public String getBrand() {
    return brand;
  }

  public void setBrand(String value) {
    brand = value;
  }

  public int getDiscountPercent() {
    return discountPercent;
  }

  public void setDiscountPercent(int value) {
    discountPercent = value;
  }

  public long getQuantitySold() {
    return quantitySold;
  }

  public Category getCategory() {
    return category;
  }

  public void setCategory(Category value) {
    category = value;
  }

  public List<ProductVariant> getVariants() {
    return variants;
  }

  public void setVariants(List<ProductVariant> value) {
    variants = value;
    value.forEach(v -> v.setProduct(this));
  }

  public List<ProductAttribute> getAttributes() {
    return attributes;
  }

  public void setAttributes(List<ProductAttribute> value) {
    attributes = value;
    value.forEach(a -> a.setProduct(this));
  }

  public List<Image> getImages() {
    return images;
  }

  public void setImages(List<Image> value) {
    images = value;
  }

  public List<Review> getReviews() {
    return reviews;
  }

  public void setReviews(List<Review> value) {
    reviews = value;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime value) {
    createdAt = value;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime value) {
    updatedAt = value;
  }

  public long getMinPrice() {
    return minPrice;
  }

  public int getTotalStock() {
    return totalStock;
  }

  public int getActiveVariantCount() {
    return activeVariantCount;
  }

  public double getAverageRating() {
    return averageRating;
  }

  public long getNumRatings() {
    return numRatings;
  }
}
