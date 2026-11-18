package com.threeroun.auctionengine.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

// S3 호환 오브젝트 스토리지(클러스터의 MinIO)에 저장한다. 오브젝트 키는 파일명 그대로라서,
// 로컬 디스크에 있던 기존 이미지를 같은 이름으로 버킷에 복사하면 DB의 imageUrl을 고칠 필요가 없다.
// 빈 등록은 S3StorageConfig가 한다 (app.image-storage.type=s3일 때만).
public class S3ProductImageStorage implements ProductImageStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ProductImageStorage.class);

    private final S3Client s3Client;
    private final String bucket;

    public S3ProductImageStorage(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    // 기동 시점에 버킷이 없으면 만들어 둔다. MinIO가 아직 안 떠 있어도 엔진 기동(상품 조회/입찰)까지
    // 막지는 않도록 실패는 경고만 남긴다 - 그 경우 첫 업로드 때 save()에서 다시 만든다.
    public void ensureBucket() {
        try {
            createBucketIfMissing();
        } catch (RuntimeException e) {
            log.warn("상품 이미지 버킷 확인에 실패했습니다. 첫 업로드 때 다시 시도합니다: bucket={}", bucket, e);
        }
    }

    @Override
    public void save(String filename, byte[] content, String contentType) {
        try {
            putObject(filename, content, contentType);
        } catch (NoSuchBucketException e) {
            createBucketIfMissing();
            putObject(filename, content, contentType);
        }
    }

    @Override
    public Optional<byte[]> load(String filename) {
        try {
            return Optional.of(s3Client.getObjectAsBytes(b -> b.bucket(bucket).key(filename)).asByteArray());
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return Optional.empty();
        }
    }

    private void putObject(String filename, byte[] content, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(filename)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(content));
    }

    private void createBucketIfMissing() {
        try {
            s3Client.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            try {
                s3Client.createBucket(b -> b.bucket(bucket));
                log.info("상품 이미지 버킷을 만들었습니다: bucket={}", bucket);
            } catch (BucketAlreadyOwnedByYouException alreadyCreated) {
                // 다른 Pod가 동시에 만든 경우 - 이미 있으니 그대로 진행
            }
        }
    }
}
