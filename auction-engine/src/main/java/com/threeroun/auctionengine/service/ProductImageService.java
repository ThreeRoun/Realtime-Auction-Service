package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.storage.ProductImageStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class ProductImageService {

    private final ProductRepository productRepository;
    private final ProductImageStorage imageStorage;
    private final TransactionTemplate transactionTemplate;

    public ProductImageService(ProductRepository productRepository, ProductImageStorage imageStorage,
                               TransactionTemplate transactionTemplate) {
        this.productRepository = productRepository;
        this.imageStorage = imageStorage;
        this.transactionTemplate = transactionTemplate;
    }

    // 파일 저장은 트랜잭션 밖에서 한다 - 저장소가 MinIO(네트워크 호출)일 때 업로드하는 동안
    // DB 커넥션을 붙잡고 있지 않게 하기 위해서다. 그래서 메서드 전체가 아니라 imageUrl을 바꾸는
    // 부분만 TransactionTemplate으로 감싼다 (같은 클래스 안 호출이라 @Transactional은 프록시를 안 탄다).
    public Product uploadImage(UUID productId, UUID requesterId, String extension, String contentType,
                               byte[] content) {
        // 파일을 올리기 전에 권한부터 확인해서, 권한 없는 요청이 저장소에 파일을 남기지 않게 한다.
        Product product = findOwnedProduct(productId, requesterId);

        String filename = UUID.randomUUID() + "." + extension;
        imageStorage.save(filename, content, contentType);

        return transactionTemplate.execute(status -> {
            // ProductController가 반환값으로 ProductResponse.from()을 호출하면서 seller.getUsername()을
            // 읽으므로 join fetch로 미리 초기화해둬야 한다(안 그러면 LazyInitializationException).
            // 위에서 읽은 product는 이미 트랜잭션이 끝난 detached 상태라, 여기서 다시 읽어 변경 감지로 반영한다.
            Product managed = productRepository.findByIdWithSeller(product.getId())
                    .orElseThrow(() -> new ProductNotFoundException(productId));
            managed.setImageUrl("/images/" + filename);
            return managed;
        });
    }

    private Product findOwnedProduct(UUID productId, UUID requesterId) {
        Product product = productRepository.findByIdWithSeller(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (!product.getSeller().getId().equals(requesterId)) {
            throw new ProductImageUploadNotAllowedException(productId);
        }
        return product;
    }
}
