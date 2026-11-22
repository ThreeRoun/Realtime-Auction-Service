package com.threeroun.auctionengine.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

// 이 서버(컨테이너) 로컬 디스크에 저장한다. 다른 Pod와 공유되지 않으므로 replica가 여러 개인
// 배포에서는 s3 구현을 써야 한다 - 로컬 개발(docker-compose)과 테스트용 기본값.
@Component
@ConditionalOnProperty(name = "app.image-storage.type", havingValue = "local", matchIfMissing = true)
public class LocalProductImageStorage implements ProductImageStorage {

    private final Path storageDir;

    public LocalProductImageStorage(@Value("${app.product-image-dir}") String storageDir) {
        this.storageDir = Path.of(storageDir);
    }

    @Override
    public void save(String filename, byte[] content, String contentType) {
        try {
            Files.createDirectories(storageDir);
            Files.write(storageDir.resolve(filename), content);
        } catch (IOException e) {
            throw new UncheckedIOException("상품 이미지 저장에 실패했습니다: " + filename, e);
        }
    }

    @Override
    public Optional<byte[]> load(String filename) {
        try {
            return Optional.of(Files.readAllBytes(storageDir.resolve(filename)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("상품 이미지 읽기에 실패했습니다: " + filename, e);
        }
    }
}
