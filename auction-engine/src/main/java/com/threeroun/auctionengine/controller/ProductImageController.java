package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.storage.ProductImageStorage;
import com.threeroun.auctionengine.storage.ProductImageTypes;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

// 상품 이미지(/images/<파일명>)를 저장소(ProductImageStorage)에서 읽어 그대로 내려준다.
// 저장소가 MinIO여도 URL은 그대로라서, DB의 imageUrl, 프론트, nginx(/images/ -> 엔진) 설정을 바꿀 필요가 없다.
// 어느 Pod로 요청이 가도 같은 저장소를 읽으므로 replica가 여러 개여도 일관되게 보인다 (이슈 #101).
@RestController
public class ProductImageController {

    // 파일명이 업로드마다 새 UUID라서 같은 URL의 내용은 바뀌지 않는다 - 브라우저가 오래 캐시해도 된다.
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final ProductImageStorage imageStorage;

    public ProductImageController(ProductImageStorage imageStorage) {
        this.imageStorage = imageStorage;
    }

    @GetMapping("/images/{filename}")
    public ResponseEntity<byte[]> get(@PathVariable String filename) {
        if (!ProductImageTypes.isValidFilename(filename)) {
            return ResponseEntity.notFound().build();
        }
        return imageStorage.load(filename)
                .map(content -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(ProductImageTypes.contentTypeFor(filename)))
                        .cacheControl(IMMUTABLE)
                        .body(content))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
