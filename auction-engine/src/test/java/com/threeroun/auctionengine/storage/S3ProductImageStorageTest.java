package com.threeroun.auctionengine.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 실제 S3 서버 없이 S3Client를 mock으로 바꿔서, 버킷/키/content-type을 맞게 넘기는지와
// 버킷·오브젝트가 없을 때의 처리만 검증한다. 실제 서버(SeaweedFS)와의 연동은 배포 후 확인한다.
@SuppressWarnings("unchecked")
class S3ProductImageStorageTest {

    private static final String BUCKET = "product-images";
    private static final String FILENAME = "0b8f2c1e-7a8b-4c5d-9e0f-1a2b3c4d5e6f.png";

    private final S3Client s3Client = mock(S3Client.class);
    private final S3ProductImageStorage storage = new S3ProductImageStorage(s3Client, BUCKET);

    @Test
    void 저장하면_버킷과_파일명_키와_content_type으로_올린다() {
        storage.save(FILENAME, "bytes".getBytes(), "image/png");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(FILENAME);
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    void 저장할_때_버킷이_없으면_만들고_다시_올린다() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(NoSuchBucketException.builder().build())
                .thenReturn(PutObjectResponse.builder().build());
        when(s3Client.headBucket(any(Consumer.class))).thenThrow(NoSuchBucketException.builder().build());

        storage.save(FILENAME, "bytes".getBytes(), "image/png");

        verify(s3Client).createBucket(any(Consumer.class));
        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 조회하면_오브젝트_내용을_돌려준다() {
        when(s3Client.getObjectAsBytes(any(Consumer.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "bytes".getBytes()));

        assertThat(storage.load(FILENAME)).hasValue("bytes".getBytes());

        ArgumentCaptor<Consumer<GetObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).getObjectAsBytes(captor.capture());
        GetObjectRequest.Builder builder = GetObjectRequest.builder();
        captor.getValue().accept(builder);
        assertThat(builder.build().bucket()).isEqualTo(BUCKET);
        assertThat(builder.build().key()).isEqualTo(FILENAME);
    }

    @Test
    void 오브젝트가_없으면_빈_값을_돌려준다() {
        when(s3Client.getObjectAsBytes(any(Consumer.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThat(storage.load(FILENAME)).isEmpty();
    }

    @Test
    void 기동_시_버킷이_이미_있으면_만들지_않는다() {
        storage.ensureBucket();

        verify(s3Client).headBucket(any(Consumer.class));
        verify(s3Client, never()).createBucket(any(Consumer.class));
    }

    @Test
    void 기동_시_저장소에_연결하지_못해도_예외를_던지지_않는다() {
        doThrow(new RuntimeException("connection refused")).when(s3Client).headBucket(any(Consumer.class));

        storage.ensureBucket();

        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void 헤드버킷_요청은_설정한_버킷을_대상으로_한다() {
        storage.ensureBucket();

        ArgumentCaptor<Consumer<HeadBucketRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).headBucket(captor.capture());
        HeadBucketRequest.Builder builder = HeadBucketRequest.builder();
        captor.getValue().accept(builder);
        assertThat(builder.build().bucket()).isEqualTo(BUCKET);
    }
}
