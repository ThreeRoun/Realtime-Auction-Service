package com.threeroun.auctionengine.service;

import java.util.List;
import java.util.UUID;

// topBidderId: 취소를 반영한 뒤의 최고 입찰자 (입찰이 하나도 안 남았으면 null)
// cancelledBidIds: 이번 요청으로 취소된 입찰 전부 (본인 입찰 + 크레딧 부족으로 건너뛴 입찰)
// availableCredit: 취소한 사용자의 취소 후 사용 가능 크레딧
public record BidCancelResult(UUID bidId, UUID productId, int currentPrice, UUID topBidderId,
                              List<UUID> cancelledBidIds, long availableCredit) {
}
