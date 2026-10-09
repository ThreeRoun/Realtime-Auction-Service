package com.threeroun.auctionengine.repository;

import com.threeroun.auctionengine.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // 낙찰 확정 시 크레딧 차감용: 같은 유저가 동시에 여러 경매에서 낙찰되는 경우를 대비해
    // row를 잠그고 조회한다 (SELECT ... FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Optional<User> findByUsername(String username);
}
