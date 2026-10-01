package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;

// extended는 "이 요청 자체가 지금 막 연장을 발생시켰는지" 여부다.
// Bid 엔티티에 이 정보를 넣지 않는 이유: UI/API 응답 전용 정보라 도메인 엔티티에 둘 이유가 없음.
public record BidResult(Bid bid, boolean extended) {
}
