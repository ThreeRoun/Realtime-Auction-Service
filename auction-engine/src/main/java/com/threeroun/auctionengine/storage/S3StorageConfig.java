package com.threeroun.auctionengine.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

// app.image-storage.type=s3일 때만 S3 클라이언트와 S3ProductImageStorage를 등록한다.
// 대상은 클러스터 안 S3 호환 서버(SeaweedFS)라서 endpoint를 직접 지정하고, path-style 주소(http://auction-s3:8333/<bucket>/<key>)를 쓴다
// (기본값인 virtual-hosted style은 <bucket>.auction-s3 같은 DNS 이름이 필요해서 클러스터 안에서 동작하지 않는다).
@Configuration
@ConditionalOnProperty(name = "app.image-storage.type", havingValue = "s3")
public class S3StorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client productImageS3Client(@Value("${app.image-storage.s3.endpoint}") String endpoint,
                                          @Value("${app.image-storage.s3.region}") String region,
                                          @Value("${app.image-storage.s3.access-key}") String accessKey,
                                          @Value("${app.image-storage.s3.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                // SeaweedFS는 region을 실제로 쓰지 않지만 SDK 요청 서명에 값이 필요하다
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }

    @Bean(initMethod = "ensureBucket")
    public S3ProductImageStorage s3ProductImageStorage(S3Client productImageS3Client,
                                                        @Value("${app.image-storage.s3.bucket}") String bucket) {
        return new S3ProductImageStorage(productImageS3Client, bucket);
    }
}
