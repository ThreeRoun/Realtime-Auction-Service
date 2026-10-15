package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.ProductImageService;
import com.threeroun.auctionengine.service.ProductNotFoundException;
import com.threeroun.auctionengine.service.UserNotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.threeroun.auctionengine.security.JwtAuthenticationFilter.AUTH_USER_ID_ATTRIBUTE;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    // status 파라미터가 없을 때의 기본값: 이슈 #10(프론트) 요구사항대로 "진행중인 상품"만 노출.
    // EXTENDED(마감연장)도 입찰 가능한 진행중 상태이므로 함께 포함한다
    // (BidService가 입찰을 허용하는 상태 집합과 반드시 일치시켜야 프론트에 보이는 상품 = 입찰 가능한 상품이 된다).
    private static final List<ProductStatus> DEFAULT_STATUSES = List.of(ProductStatus.IN_PROGRESS, ProductStatus.EXTENDED);

    // 업로드 허용 이미지 형식 - 확장자는 클라이언트가 보낸 원본 파일명을 그대로 믿지 않고
    // (경로 조작 방지) content-type으로부터 직접 매핑해서 정한다.
    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final ProductImageService productImageService;

    public ProductController(ProductRepository productRepository, UserRepository userRepository,
                              BidRepository bidRepository, ProductImageService productImageService) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.productImageService = productImageService;
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

    @GetMapping("/{id}")
    public ProductResponse detail(@PathVariable UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return ProductResponse.from(product);
    }

    // 입찰 로그 테이블 전체를 그대로 보여준다 (진 입찰 포함) - README의 "로그 테이블" 방침대로
    // 입찰 이력/증빙 용도로 쓰기 위함.
    @GetMapping("/{id}/bids")
    public List<BidHistoryResponse> bidHistory(@PathVariable UUID id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        return bidRepository.findByProductIdOrderByBidAtDesc(id).stream()
                .map(BidHistoryResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductCreateRequest request,
                                   @RequestAttribute(AUTH_USER_ID_ATTRIBUTE) UUID sellerId) {
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new UserNotFoundException(sellerId));

        // 프론트 상품 등록 폼에 시작 시각 입력란이 없어서(등록하면 바로 시작한다고 가정) startAt 생략을 허용하고,
        // 생략됐으면 "지금"으로 채운다.
        LocalDateTime startAt = request.startAt() != null ? request.startAt() : LocalDateTime.now();

        if (!request.endAt().isAfter(startAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endAt은 startAt보다 뒤여야 합니다");
        }

        Product product = new Product(request.title(), request.description(), request.startingPrice(),
                request.bidUnit(), seller, startAt, request.endAt());

        // 마감 연장/낙찰 처리를 자동으로 돌리는 스케줄러가 아직 없어서, 상품 상태 전이는 지금 여기
        // "생성 시점에 시작 시각이 이미 지났으면 바로 진행중으로 만든다"는 것 하나뿐이다.
        // (스케줄러가 생기기 전까지는 start_at이 미래인 PENDING 상품이 스스로 IN_PROGRESS로 안 바뀐다.)
        if (!startAt.isAfter(LocalDateTime.now())) {
            product.setStatus(ProductStatus.IN_PROGRESS);
        }

        return ProductResponse.from(productRepository.save(product));
    }

    // 상품 등록(JSON body)과 별도 엔드포인트로 둔 이유: 기존 POST /api/products가 JSON body라
    // multipart/form-data로 바꾸면 기존 연동(프론트/테스트)이 다 깨진다. 등록 후 이 엔드포인트로
    // 이미지를 따로 올리는 2단계 흐름이 MVP 범위에서 변경 폭이 제일 작다.
    @PostMapping(value = "/{id}/image", consumes = "multipart/form-data")
    public ProductResponse uploadImage(@PathVariable UUID id,
                                        @RequestPart("image") MultipartFile image,
                                        @RequestAttribute(AUTH_USER_ID_ATTRIBUTE) UUID requesterId) {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 파일이 비어있습니다");
        }
        String extension = ALLOWED_IMAGE_TYPES.get(image.getContentType());
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 이미지 형식입니다 (jpeg/png/webp만 허용)");
        }

        byte[] content;
        try {
            content = image.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 파일을 읽을 수 없습니다");
        }

        Product product = productImageService.uploadImage(id, requesterId, extension, content);
        return ProductResponse.from(product);
    }

    private ProductStatus parseStatus(String status) {
        try {
            return ProductStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 status 값입니다: " + status);
        }
    }
}
