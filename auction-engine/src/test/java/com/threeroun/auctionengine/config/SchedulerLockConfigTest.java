package com.threeroun.auctionengine.config;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// AuctionScheduler의 @SchedulerLock이 실제로 의존하는 메커니즘(이슈 #100) - 같은 이름의 락을
// 여러 "인스턴스"(k8s Pod에 해당)가 동시에 요청해도 단 하나만 획득하는지 - 를 직접 검증한다.
// 스케줄러 메서드 자체를 동시 호출하는 것보다, 이 락 제공자 레벨에서 확인하는 게 더 직접적이다.
@SpringBootTest
class SchedulerLockConfigTest {

    @Autowired
    private LockProvider lockProvider;

    @Test
    void 같은_이름의_락은_동시에_요청해도_단_하나만_획득한다() throws InterruptedException {
        String lockName = "test-lock-" + UUID.randomUUID();
        int instanceCount = 10; // k8s Pod 10개가 동시에 같은 스케줄을 깨우는 상황을 흉내 낸다
        LockConfiguration config = new LockConfiguration(
                Instant.now(), lockName, Duration.ofSeconds(10), Duration.ofSeconds(1));

        ExecutorService executor = Executors.newFixedThreadPool(instanceCount);
        CountDownLatch readyLatch = new CountDownLatch(instanceCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(instanceCount);
        AtomicInteger acquiredCount = new AtomicInteger();

        for (int i = 0; i < instanceCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    Optional<SimpleLock> lock = lockProvider.lock(config);
                    if (lock.isPresent()) {
                        acquiredCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 대기 중이던 10개를 동시에 출발시킨다
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("모든 스레드가 15초 안에 끝나야 한다").isTrue();
        // 핵심 검증: 10개가 동시에 같은 락을 요청해도 정확히 1개만 획득해야 한다
        assertThat(acquiredCount.get()).isEqualTo(1);
    }
}
