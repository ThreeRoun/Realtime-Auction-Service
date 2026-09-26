package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.UserNotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    // status 파라미터가 없을 때의 기본값: 이슈 #10(프론트) 요구사항대로 "진행중인 상품"만 노출.
    // EXTENDED(마감연장)도 입찰 가능한 진행중 상태이므로 함께 포함한다
    // (BidService가 입찰을 허용하는 상태 집합과 반드시 일치시켜야 프론트에 보이는 상품 = 입찰 가능한 상품이 된다).
    private static final List<ProductStatus> DEFAULT_STATUSES = List.of(ProductStatus.IN_PROGRESS, ProductStatus.EXTENDED);

    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public ProductController(ProductRepository productRepository, UserRepository userRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductCreateRequest request) {
        User seller = userRepository.findById(request.sellerId())
                .orElseThrow(() -> new UserNotFoundException(request.sellerId()));

        if (!request.endAt().isAfter(request.startAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endAt은 startAt보다 뒤여야 합니다");
        }

        Product product = new Product(request.title(), request.description(), request.startingPrice(),
                request.bidUnit(), seller, request.startAt(), request.endAt());

        // 마감 연장/낙찰 처리를 자동으로 돌리는 스케줄러가 아직 없어서, 상품 상태 전이는 지금 여기
        // "생성 시점에 시작 시각이 이미 지났으면 바로 진행중으로 만든다"는 것 하나뿐이다.
        // (스케줄러가 생기기 전까지는 start_at이 미래인 PENDING 상품이 스스로 IN_PROGRESS로 안 바뀐다.)
        if (!request.startAt().isAfter(LocalDateTime.now())) {
            product.setStatus(ProductStatus.IN_PROGRESS);
        }

        return ProductResponse.from(productRepository.save(product));
    }

    private ProductStatus parseStatus(String status) {
        try {
            return ProductStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 status 값입니다: " + status);
        }
    }
}
