package com.threeroun.auctionengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private Integer credit = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected User() {
    }

    public User(String username, String email, String passwordHash) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    // 회원가입 시점에 초기 크레딧을 받는 생성자. 결제 연동이 없는 MVP라 "충전" API가 따로
    // 없으므로, 데모/테스트에서 입찰이 가능하려면 가입 시 크레딧을 바로 부여할 수 있어야 한다.
    public User(String username, String email, String passwordHash, int initialCredit) {
        this(username, email, passwordHash);
        this.credit = initialCredit;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Integer getCredit() {
        return credit;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 낙찰 확정 시점에만 차감된다 (입찰 시점엔 크레딧을 잠그지 않음)
    public void deductCredit(int amount) {
        if (amount > credit) {
            throw new IllegalStateException("크레딧이 부족합니다: 보유=" + credit + ", 필요=" + amount);
        }
        credit -= amount;
    }
}
