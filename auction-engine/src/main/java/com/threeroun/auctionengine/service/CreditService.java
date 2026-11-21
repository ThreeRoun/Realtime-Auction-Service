package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// 크레딧 정책: 입찰하면 "내가 최고 입찰자인 진행중 경매"의 현재가만큼 크레딧이 묶이고,
// 사용 가능 크레딧 = 보유 크레딧 - 묶인 크레딧이다. 다른 사람이 더 높게 입찰하면(추월) 그 경매의
// 묶임은 저절로 풀리고, 마감되면 낙찰 차감(AuctionClosingService)으로 바뀐다.
@Service
public class CreditService {

    // 입찰을 받는 상태(= 크레딧이 묶여 있을 수 있는 상태)
    private static final List<ProductStatus> LIVE_STATUSES = List.of(ProductStatus.IN_PROGRESS, ProductStatus.EXTENDED);

    private final ProductRepository productRepository;

    public CreditService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // 마이페이지 등에 보여줄 사용 가능 크레딧
    public long availableCredit(User user) {
        return user.getCredit() - productRepository.sumLockedCredit(user.getId(), LIVE_STATUSES);
    }

    // 입찰 판정용: 지금 입찰하려는 상품(excludedProductId)을 뺀, 다른 경매에서 쓸 수 있는 크레딧.
    // 이 상품에 이미 내가 최고 입찰자로 묶어 둔 돈은 새 입찰가로 대체되므로 빼서 계산한다
    // (그래서 같은 경매에서 입찰가를 올릴 때는 늘어나는 차액만큼만 더 필요하다).
    public long availableCreditExcluding(User user, UUID excludedProductId) {
        return user.getCredit()
                - productRepository.sumLockedCreditExcluding(user.getId(), excludedProductId, LIVE_STATUSES);
    }
}
