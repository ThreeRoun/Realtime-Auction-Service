package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.service.BidResult;
import com.threeroun.auctionengine.service.BidService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bids")
public class BidController {

    private final BidService bidService;

    public BidController(BidService bidService) {
        this.bidService = bidService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BidResponse placeBid(@Valid @RequestBody BidRequest request) {
        BidResult result = bidService.placeBid(request.productId(), request.bidderId(), request.amount());
        return BidResponse.from(result);
    }
}
