package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.service.BidCancelResult;

import java.util.List;
import java.util.UUID;

public record BidCancelResponse(
        UUID bidId,
        UUID productId,
        int currentPrice,
        UUID topBidderId,
        List<UUID> cancelledBidIds,
        long availableCredit
) {
    public static BidCancelResponse from(BidCancelResult result) {
        return new BidCancelResponse(result.bidId(), result.productId(), result.currentPrice(),
                result.topBidderId(), result.cancelledBidIds(), result.availableCredit());
    }
}
