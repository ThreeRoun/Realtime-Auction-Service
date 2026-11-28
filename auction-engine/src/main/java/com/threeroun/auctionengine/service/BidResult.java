package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;

// extended는 "이 요청 자체가 지금 막 연장을 발생시켰는지" 여부다.
// Bid 엔티티에 이 정보를 넣지 않는 이유: UI/API 응답 전용 정보라 도메인 엔티티에 둘 이유가 없음.
//
// reason: 입찰이 가격을 못 넘겨 isValid=false가 된 사유 코드. 유효한 입찰이면 null.
// 다른 거절 사유(본인 상품, 쿨다운, 마감 등)는 예외로 던져 HTTP 오류 코드로 전달되므로 여기에 오지 않는다.
// minNextBid: 이 입찰을 처리한 직후 기준으로, 다음 입찰이 유효하려면 필요한 최소 금액(현재가 + 입찰 단위).
// 상품 row를 잠근 채로 계산하므로 동시 입찰 상황에서도 "이 순간의 정확한 값"이다.
//
// availableCredit: 이 입찰을 처리한 직후 입찰자의 사용 가능 크레딧(보유 - 진행중 경매에 묶인 금액).
// 화면이 입찰 직후 크레딧 표시를 바로 갱신할 수 있게 응답에 싣는다 (이슈 #185).
public record BidResult(Bid bid, boolean extended, String reason, int minNextBid, long availableCredit) {

    public static final String AMOUNT_TOO_LOW = "AMOUNT_TOO_LOW";
    // 사용 가능 크레딧(보유 - 다른 진행중 경매에 묶인 금액)보다 높은 금액으로 입찰한 경우
    public static final String INSUFFICIENT_CREDIT = "INSUFFICIENT_CREDIT";
}
