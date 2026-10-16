package com.kyro.catalog;

import com.kyro.catalog.client.OrderClient;
import com.kyro.catalog.dto.*;
import com.kyro.catalog.messaging.ProductEventPublisher;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
  private static final Logger log = LoggerFactory.getLogger(ProductService.class);

  private final ProductRepository products;
  private final ProductVariantRepository variants;
  private final CategoryRepository categories;
  private final ProductEventPublisher events;
  private final OrderClient orders;
  private final ProcessedStockReservationRepository stockReservations;

  public ProductService(
      ProductRepository products,
      CategoryRepository categories,
      ReviewRepository ignoredReviews,
      ImageService ignoredImages,
      ProductEventPublisher events,
      OrderClient orders,
      ProductVariantRepository variants,
      ProcessedStockReservationRepository stockReservations) {
    this.products = products;
    this.categories = categories;
    this.events = events;
    this.orders = orders;
    this.variants = variants;
    this.stockReservations = stockReservations;
  }

  @Transactional(readOnly = true)
  public List<Product> findProductsByIds(List<Long> ids) {
    return products.findAllById(ids);
  }

  @Transactional
  public Product createProduct(CreateProductRequest r) {
    Product p = new Product();
    apply(
        p,
        r.title(),
        r.description(),
        r.detailedReview(),
        r.brand(),
        r.discountPercent(),
        r.topLevelCategory(),
        r.secondLevelCategory(),
        r.variants(),
        r.attributes());
    Product saved = products.save(p);
    events.publishProductCreated(saved);
    return saved;
  }

  @Transactional
  public String deleteProduct(Long id) {
    adminDeleteProduct(id);
    return "Product disabled successfully";
  }

  @Transactional(readOnly = true)
  public Product findProductById(Long id) {
    return products
        .findById(id)
        .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
  }

  public List<Product> searchProducts(String keyword) {
    return products.findByTitleContainingIgnoreCase(keyword);
  }

  public List<Product> findProductByCategory(String name) {
    Category c = categories.findByName(name);
    if (c == null) return List.of();
    List<Long> ids = new ArrayList<>(List.of(c.getId()));
    if (c.getParentCategory() == null)
      categories.findByParentCategoryId(c.getId()).forEach(x -> ids.add(x.getId()));
    return products.findByCategoryIdIn(ids);
  }

  @Transactional(readOnly = true)
  public Page<ProductDTO> getProductsWithFilter(
      Pageable pageable, FilterProduct f, boolean includeInactive) {
    validateProductFilter(f);
    List<Long> categoryIds = categoryIds(f.getCategoryId());
    Specification<Product> spec =
        (root, query, cb) -> {
          List<Predicate> ps = new ArrayList<>();
          String keyword = clean(f.getKeyword());
          if (keyword != null) {
            String q = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            ps.add(
                cb.or(
                    cb.like(cb.lower(root.get("title")), q),
                    cb.like(cb.lower(root.get("description")), q),
                    cb.like(cb.lower(root.get("brand")), q)));
          }
          if (categoryIds != null) ps.add(root.get("category").get("id").in(categoryIds));
          if (clean(f.getBrand()) != null)
            ps.add(
                cb.equal(
                    cb.lower(root.get("brand")), clean(f.getBrand()).toLowerCase(Locale.ROOT)));
          if (f.getMinPrice() != null) ps.add(cb.ge(root.get("minPrice"), f.getMinPrice()));
          if (f.getMaxPrice() != null) ps.add(cb.le(root.get("minPrice"), f.getMaxPrice()));
          if (f.getInStock() != null)
            ps.add(
                f.getInStock()
                    ? cb.gt(root.get("totalStock"), 0)
                    : cb.equal(root.get("totalStock"), 0));
          if (f.getMinRating() != null) ps.add(cb.ge(root.get("averageRating"), f.getMinRating()));
          if (!includeInactive) ps.add(cb.gt(root.get("activeVariantCount"), 0));
          return cb.and(ps.toArray(Predicate[]::new));
        };
    return products.findAll(spec, pageable).map(ProductDTO::new);
  }

  static Pageable productPageable(int page, int size, List<String> values, boolean admin) {
    if (page < 0 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid page or size");
    Map<String, String> fields =
        new HashMap<>(
            Map.of(
                "id",
                "id",
                "title",
                "title",
                "brand",
                "brand",
                "price",
                "minPrice",
                "minPrice",
                "minPrice",
                "discountPercent",
                "discountPercent",
                "createdAt",
                "createdAt",
                "averageRating",
                "averageRating"));
    if (admin) fields.put("quantity", "totalStock");
    if (admin) fields.put("quantitySold", "quantitySold");
    List<String> tokens = sortTokens(values);
    List<Sort.Order> sort = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i += 2) {
      String field = fields.get(tokens.get(i));
      if (field == null)
        throw new IllegalArgumentException("Unsupported product sort: " + tokens.get(i));
      sort.add(new Sort.Order(Sort.Direction.fromString(tokens.get(i + 1)), field));
    }
    if (sort.isEmpty()) sort.add(Sort.Order.desc("createdAt"));
    if (sort.stream().noneMatch(x -> x.getProperty().equals("id")))
      sort.add(new Sort.Order(sort.get(0).getDirection(), "id"));
    return PageRequest.of(page, size, Sort.by(sort));
  }

  public Map<String, Object> getAdminFilterStatistics() {
    List<Product> all = products.findAll();
    return Map.of(
        "totalProducts",
        all.size(),
        "totalStock",
        all.stream().mapToInt(Product::getTotalStock).sum(),
        "totalInventoryValue",
        all.stream()
            .mapToLong(
                p ->
                    p.getVariants().stream()
                        .filter(ProductVariant::isActive)
                        .mapToLong(v -> v.getPrice() * v.getStock())
                        .sum())
            .sum());
  }

  public Map<String, Object> getAllCategories() {
    return Map.of(
        "topLevelCategories",
        categories.findByParentCategoryIsNull().stream().map(Category::getName).toList());
  }

  @Transactional
  public void adminDeleteProduct(Long id) {
    Product p = findProductById(id);
    p.getVariants().forEach(v -> v.setActive(false));
    products.save(p);
    events.publishProductDeleted(id);
  }

  public List<Map<String, Object>> getTopSellingProducts(int limit) {
    if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    var sales = orders.getTopSellingProducts(limit);
    return mapTopSellingProducts(
        sales,
        products.findAllById(
            sales.stream().map(OrderClient.TopSellingProductResponse::productId).toList()));
  }

  static List<Map<String, Object>> mapTopSellingProducts(
      List<OrderClient.TopSellingProductResponse> sales, List<Product> products) {
    Map<Long, Product> byId = new HashMap<>();
    products.forEach(p -> byId.put(p.getId(), p));
    return sales.stream()
        .filter(s -> byId.containsKey(s.productId()))
        .map(
            s -> {
              ProductDTO p = new ProductDTO(byId.get(s.productId()));
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", p.id());
              m.put("title", p.title());
              m.put("minPrice", p.minPrice());
              m.put("minSalePrice", p.minSalePrice());
              m.put("quantitySold", s.quantitySold());
              return m;
            })
        .toList();
  }

  public Map<String, Object> getRevenueByCateogry() {
    var revenue = orders.getProductRevenue();
    Map<Long, Product> byId = new HashMap<>();
    products
        .findAllById(revenue.stream().map(OrderClient.ProductRevenueResponse::productId).toList())
        .forEach(p -> byId.put(p.getId(), p));
    Map<String, Long> grouped = new LinkedHashMap<>();
    revenue.forEach(
        r -> {
          Product p = byId.get(r.productId());
          if (p != null) {
            Category c = p.getCategory();
            String name =
                c.getParentCategory() == null ? c.getName() : c.getParentCategory().getName();
            grouped.merge(name, r.revenue(), Long::sum);
          }
        });
    return Map.of("categoryRevenue", grouped);
  }

  @Transactional
  public Product updateProduct(Long id, Product incoming) {
    Product p = findProductById(id);
    if (incoming.getTitle() != null) p.setTitle(incoming.getTitle());
    if (incoming.getDescription() != null) p.setDescription(incoming.getDescription());
    if (incoming.getBrand() != null) p.setBrand(incoming.getBrand());
    p.setDiscountPercent(incoming.getDiscountPercent());
    events.publishProductUpdated(p);
    return products.save(p);
  }

  @Transactional
  public ProductDTO updateProductByID(Long id, UpdateProductRequest r) {
    Product p = findProductById(id);
    apply(
        p,
        r.title() == null ? p.getTitle() : r.title(),
        r.description() == null ? p.getDescription() : r.description(),
        r.detailedReview() == null ? p.getDetailedReview() : r.detailedReview(),
        r.brand() == null ? p.getBrand() : r.brand(),
        r.discountPercent() == null ? p.getDiscountPercent() : r.discountPercent(),
        r.topLevelCategory(),
        r.secondLevelCategory(),
        r.variants(),
        r.attributes());
    events.publishProductUpdated(p);
    return new ProductDTO(products.save(p));
  }

  @Transactional
  public void adjustStock(Long variantId, int delta) {
    ProductVariant v =
        variants
            .findByIdWithLock(variantId)
            .orElseThrow(() -> new EntityNotFoundException("Variant not found: " + variantId));
    if (!v.isActive()) throw new IllegalStateException("Variant is inactive");
    int next = v.getStock() + delta;
    if (next < 0) throw new IllegalStateException("Insufficient stock");
    v.setStock(next);
  }

  @Transactional
  public boolean reserveStock(Long orderId, List<Map<String, Object>> items) {
    if (orderId != null && stockReservations.existsById(orderId)) {
      log.info("Stock for Order ID #{} already reserved, skipping duplicate deduction", orderId);
      return true;
    }
    for (Map<String, Object> item : items) {
      Long variantId = Long.valueOf(item.get("variantId").toString());
      int quantity = Integer.parseInt(item.get("quantity").toString());
      adjustStock(variantId, -quantity);
    }
    if (orderId != null) {
      stockReservations.save(new ProcessedStockReservation(orderId));
    }
    return true;
  }

  @Transactional
  public void reserveStock(List<Map<String, Object>> items) {
    reserveStock(null, items);
  }

  private void apply(
      Product p,
      String title,
      String description,
      String detail,
      String brand,
      int discount,
      String top,
      String second,
      List<ProductVariant> vs,
      List<ProductAttribute> as) {
    p.setTitle(title);
    p.setDescription(description);
    p.setDetailedReview(detail);
    p.setBrand(brand);
    p.setDiscountPercent(discount);
    if (top != null || second != null) p.setCategory(resolveCategory(top, second));
    if (vs != null) replaceVariants(p, vs);
    if (as != null) replaceAttributes(p, as);
  }

  private static void replaceVariants(Product p, List<ProductVariant> incoming) {
    if (incoming.isEmpty() || incoming.stream().noneMatch(ProductVariant::isActive))
      throw new IllegalArgumentException("At least one active variant is required");
    Map<Long, ProductVariant> existing = new HashMap<>();
    p.getVariants().forEach(v -> existing.put(v.getId(), v));
    Set<Long> ids = new HashSet<>();
    Set<String> skus = new HashSet<>();
    Set<String> names = new HashSet<>();
    List<ProductVariant> next = new ArrayList<>();
    for (ProductVariant value : incoming) {
      if (value.getId() != null && !ids.add(value.getId()))
        throw new IllegalArgumentException("Duplicate variant ID: " + value.getId());
      if (!skus.add(value.getSku()))
        throw new IllegalArgumentException("Duplicate variant SKU: " + value.getSku());
      if (!names.add(value.getVariantName()))
        throw new IllegalArgumentException("Duplicate variant name: " + value.getVariantName());
      ProductVariant target = value;
      if (value.getId() != null) {
        target = existing.get(value.getId());
        if (target == null)
          throw new IllegalArgumentException(
              "Variant does not belong to product: " + value.getId());
        target.setSku(value.getSku());
        target.setVariantName(value.getVariantName());
        target.setPrice(value.getPrice());
        target.setStock(value.getStock());
        target.setActive(value.isActive());
      }
      target.setProduct(p);
      next.add(target);
    }
    p.getVariants().clear();
    p.getVariants().addAll(next);
  }

  private static void replaceAttributes(Product p, List<ProductAttribute> incoming) {
    Map<Long, ProductAttribute> existing = new HashMap<>();
    p.getAttributes().forEach(a -> existing.put(a.getId(), a));
    Set<Long> ids = new HashSet<>();
    List<ProductAttribute> next = new ArrayList<>();
    for (ProductAttribute value : incoming) {
      if (value.getId() != null && !ids.add(value.getId()))
        throw new IllegalArgumentException("Duplicate attribute ID: " + value.getId());
      ProductAttribute target = value;
      if (value.getId() != null) {
        target = existing.get(value.getId());
        if (target == null)
          throw new IllegalArgumentException(
              "Attribute does not belong to product: " + value.getId());
        target.setName(value.getName());
        target.setValue(value.getValue());
        target.setUnit(value.getUnit());
      }
      target.setProduct(p);
      next.add(target);
    }
    p.getAttributes().clear();
    p.getAttributes().addAll(next);
  }

  private Category resolveCategory(String top, String second) {
    Category parent =
        categories
            .findByNameIgnoreCase(top)
            .orElseThrow(() -> new EntityNotFoundException("Top category not found"));
    if (parent.getParentCategory() != null)
      throw new IllegalArgumentException("Top category must not have a parent");
    return second == null || second.isBlank()
        ? parent
        : categories
            .findByNameAndParentCategory(second, parent)
            .orElseThrow(() -> new EntityNotFoundException("Second category not found"));
  }

  private List<Long> categoryIds(Long id) {
    if (id == null) return null;
    Category c = categories.findById(id).orElse(null);
    if (c == null) return List.of(-1L);
    List<Long> ids = new ArrayList<>(List.of(id));
    if (c.getParentCategory() == null)
      categories.findByParentCategoryId(id).forEach(x -> ids.add(x.getId()));
    return ids;
  }

  private static String clean(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static List<String> sortTokens(List<String> values) {
    if (values == null) return List.of();
    List<String> t =
        values.stream()
            .flatMap(v -> Arrays.stream(v.split(",")))
            .map(String::trim)
            .filter(v -> !v.isEmpty())
            .toList();
    if (t.size() % 2 != 0)
      throw new IllegalArgumentException("sort must use field,direction pairs");
    return t;
  }

  private static void validateProductFilter(FilterProduct f) {
    if (f.getMinPrice() != null && f.getMinPrice() < 0
        || f.getMaxPrice() != null && f.getMaxPrice() < 0
        || f.getMinPrice() != null && f.getMaxPrice() != null && f.getMinPrice() > f.getMaxPrice())
      throw new IllegalArgumentException("Invalid price range");
    if (f.getMinRating() != null && (f.getMinRating() < 0 || f.getMinRating() > 5))
      throw new IllegalArgumentException("Invalid rating");
  }
}
