package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.repository.ProductRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    // status 파라미터가 없을 때의 기본값: 이슈 #10(프론트) 요구사항대로 "진행중인 상품"만 노출.
    // EXTENDED(마감연장)도 입찰 가능한 진행중 상태이므로 함께 포함한다
    // (BidService가 입찰을 허용하는 상태 집합과 반드시 일치시켜야 프론트에 보이는 상품 = 입찰 가능한 상품이 된다).
    private static final List<ProductStatus> DEFAULT_STATUSES = List.of(ProductStatus.IN_PROGRESS, ProductStatus.EXTENDED);

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) String status) {
        List<Product> products;
        if (status == null) {
            products = productRepository.findByStatusInOrderByEndAtAsc(DEFAULT_STATUSES);
        } else if (status.equalsIgnoreCase("all")) {
            products = productRepository.findAllByOrderByEndAtAsc();
        } else {
            products = productRepository.findByStatusOrderByEndAtAsc(parseStatus(status));
        }
        return products.stream().map(ProductResponse::from).toList();
    }

    private ProductStatus parseStatus(String status) {
        try {
            return ProductStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 status 값입니다: " + status);
        }
    }
}
