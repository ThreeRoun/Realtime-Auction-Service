package com.threeroun.auctionengine.domain;

public enum ProductStatus {
    PENDING,      // 등록대기
    IN_PROGRESS,  // 진행중
    EXTENDED,     // 마감연장
    SOLD,         // 낙찰확정
    UNSOLD,       // 유찰
    COMPLETED,    // 완료
    CANCELLED     // 경매취소 (판매자가 유효 입찰이 없을 때 취소)
}
