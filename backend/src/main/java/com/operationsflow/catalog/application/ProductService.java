package com.operationsflow.catalog.application;

import java.util.*;
import java.util.stream.Collectors;
import jakarta.persistence.criteria.Subquery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import com.operationsflow.catalog.api.ProductContracts.*;
import com.operationsflow.catalog.domain.*;
import com.operationsflow.catalog.persistence.*;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.domain.*;

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository products;
    private final SkuRepository skus;
    public ProductService(ProductRepository products, SkuRepository skus) { this.products = products; this.skus = skus; }
    public Page<View> list(String query, RecordStatus status, int page, int size, String sort, String direction) {
        String term = ListQuery.search(query).toLowerCase(Locale.ROOT);
        var pageable = ListQuery.page(page, size, sort, direction, Set.of("name", "createdAt", "status"));
        Specification<Product> spec = (root, criteria, cb) -> {
            Subquery<UUID> matchingSku = criteria.subquery(UUID.class);
            var sku = matchingSku.from(Sku.class);
            matchingSku.select(sku.get("productId")).where(cb.greaterThan(cb.locate(cb.lower(sku.get("code")), term), 0));
            var search = term.isEmpty() ? cb.conjunction() : cb.or(
                cb.greaterThan(cb.locate(cb.lower(root.get("name")), term), 0), root.get("id").in(matchingSku));
            return status == null ? search : cb.and(search, cb.equal(root.get("status"), status));
        };
        var result = products.findAll(spec, pageable);
        var grouped = result.isEmpty() ? Map.<UUID, List<Sku>>of() : skus.findByProductIdInOrderByCodeAsc(result.stream().map(Product::getId).toList())
            .stream().collect(Collectors.groupingBy(Sku::getProductId));
        return result.map(p -> view(p, grouped.getOrDefault(p.getId(), List.of())));
    }
    public View get(UUID id) { return view(find(id)); }
    @Transactional
    public View create(Create request) {
        var product = products.saveAndFlush(new Product(request.name(), request.description()));
        skus.saveAndFlush(new Sku(product.getId(), request.skuCode()));
        return view(product);
    }
    @Transactional
    public View update(UUID id, Update request) {
        var product = find(id); product.update(request.name(), request.description(), request.version());
        products.flush(); return view(product);
    }
    @Transactional
    public View addSku(UUID id, AddSku request) {
        var product = find(id); product.checkVersion(request.version()); product.touch();
        products.flush(); // Optimistic update serializes this change against edits/deactivation.
        skus.saveAndFlush(new Sku(id, request.code()));
        return view(product);
    }
    @Transactional
    public View deactivate(UUID id, Deactivate request) {
        var product = find(id); product.deactivate(request.version()); products.flush(); return view(product);
    }
    private Product find(UUID id) {
        return products.findById(id).orElseThrow(() -> new BusinessException("NOT_FOUND", "Product not found."));
    }
    private View view(Product product) { return view(product, skus.findByProductIdInOrderByCodeAsc(List.of(product.getId()))); }
    private View view(Product p, List<Sku> codes) {
        return new View(p.getId(), p.getName(), p.getDescription(), p.getStatus(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt(),
            codes.stream().map(s -> new SkuView(s.getId(), s.getCode())).toList());
    }
}
