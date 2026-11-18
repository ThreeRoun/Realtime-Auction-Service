package com.threeroun.auctionengine.storage;

import java.util.Optional;

// 상품 이미지 파일을 어디에 두는지(로컬 디스크 / S3 호환 오브젝트 스토리지)를 감춘다.
// app.image-storage.type 설정으로 구현체를 고른다:
// - local(기본값): LocalProductImageStorage. 로컬 개발/테스트용, replica 1개일 때만 일관됨
// - s3: S3ProductImageStorage. 클러스터의 S3 호환 저장소(SeaweedFS)에 저장해서 어느 Pod에서 올려도 모든 Pod가 서빙할 수 있음 (이슈 #101)
//
// filename은 ProductImageTypes.isValidFilename()을 통과한 "<uuid>.<확장자>" 형식만 넘어온다.
public interface ProductImageStorage {

    void save(String filename, byte[] content, String contentType);

    // 파일이 없으면 빈 Optional
    Optional<byte[]> load(String filename);
}
