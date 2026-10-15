package com.threeroun.auctionengine.service;

import java.util.UUID;

public class ProductImageUploadNotAllowedException extends RuntimeException {

    public ProductImageUploadNotAllowedException(UUID productId) {
        super("본인이 등록한 상품에만 이미지를 업로드할 수 있습니다: " + productId);
    }
}
