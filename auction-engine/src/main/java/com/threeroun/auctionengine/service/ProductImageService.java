package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class ProductImageService {

    private final ProductRepository productRepository;
    private final Path storageDir;

    public ProductImageService(ProductRepository productRepository,
                                @Value("${app.product-image-dir}") String storageDir) {
        this.productRepository = productRepository;
        this.storageDir = Path.of(storageDir);
    }

    // 상품 row 수정(imageUrl)을 트랜잭션 안에서 하므로, 컨트롤러에서 따로 save()를 호출할 필요 없이
    // 메서드가 끝나며 커밋될 때 변경 감지(dirty checking)로 자동 반영된다 (BidService와 동일한 패턴).
    @Transactional
    public Product uploadImage(UUID productId, UUID requesterId, String extension, byte[] content) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (!product.getSeller().getId().equals(requesterId)) {
            throw new ProductImageUploadNotAllowedException(productId);
        }

        String filename = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(storageDir);
            Files.write(storageDir.resolve(filename), content);
        } catch (IOException e) {
            throw new UncheckedIOException("상품 이미지 저장에 실패했습니다: " + productId, e);
        }

        product.setImageUrl("/images/" + filename);
        return product;
    }
}
