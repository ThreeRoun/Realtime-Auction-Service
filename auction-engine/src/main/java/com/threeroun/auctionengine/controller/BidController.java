package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.service.BidCancelService;
import com.threeroun.auctionengine.service.BidResult;
import com.threeroun.auctionengine.service.BidService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.threeroun.auctionengine.security.JwtAuthenticationFilter.AUTH_USER_ID_ATTRIBUTE;

@RestController
@RequestMapping("/api/bids")
public class BidController {

    private final BidService bidService;
    private final BidCancelService bidCancelService;

    public BidController(BidService bidService, BidCancelService bidCancelService) {
        this.bidService = bidService;
        this.bidCancelService = bidCancelService;
    }

    // 본인의 입찰을 취소한다 (이슈 #182). 규칙은 BidCancelService 참고.
    @DeleteMapping("/{bidId}")
    public BidCancelResponse cancel(@PathVariable UUID bidId,
                                    @RequestAttribute(AUTH_USER_ID_ATTRIBUTE) UUID requesterId) {
        return BidCancelResponse.from(bidCancelService.cancel(bidId, requesterId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BidResponse placeBid(@Valid @RequestBody BidRequest request,
                                 @RequestAttribute(AUTH_USER_ID_ATTRIBUTE) UUID bidderId) {
        BidResult result = bidService.placeBid(request.productId(), bidderId, request.amount());
        return BidResponse.from(result);
    }
}
