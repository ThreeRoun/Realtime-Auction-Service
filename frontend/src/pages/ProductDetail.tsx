import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { jwtDecode } from "jwt-decode";
import { getUserProfile, type UserProfile } from "../api/auth";
import type { Product } from "../data/products";
import { cancelAuction } from "../api/products";
import {
  placeBid,
  getBidHistory,
  type BidHistoryResponse,
} from "../api/bids";
import {
  connectAuctionSocket,
  connectLobbySocket,
  type SocketStatus,
} from "../websocket/auctionSocket";

// 엔진 ProductStatus 중 마감 이후의 상태 (낙찰확정, 유찰, 완료)
const CLOSED_STATUSES = ["SOLD", "UNSOLD", "COMPLETED"];

function ProductDetail() {
  const { id } = useParams();
  const [product, setProduct] = useState<Product | null>(null);
  const [user, setUser] = useState<UserProfile | null>(null);
  const [bidHistory, setBidHistory] = useState<BidHistoryResponse[]>([]);
  const [currentPrice, setCurrentPrice] = useState(0);
  const [endAt, setEndAt] = useState("");
  const [isAuctionClosed, setIsAuctionClosed] = useState(false);
  const [winnerId, setWinnerId] = useState<string | null>(null);
  const [winnerUsername, setWinnerUsername] = useState<string | null>(null);
  const [finalPrice, setFinalPrice] = useState<number | null>(null);
  const [bidAmount, setBidAmount] = useState("");
  const [isBidding, setIsBidding] = useState(false);
  const [bidMessage, setBidMessage] = useState("");
  const [socketStatus, setSocketStatus] = useState<SocketStatus>("connecting");
  const [isCancelled, setIsCancelled] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelMessage, setCancelMessage] = useState("");
    
  const profileRequestId = useRef(0);
  const profileRefreshTimer = useRef<number | null>(null);

  const refreshUserProfile = useCallback(async () => {
    const requestId = ++profileRequestId.current;
    const accessToken = localStorage.getItem("accessToken");

    if (!accessToken) {
      return;
    }

    try {
      const decoded = jwtDecode<{ sub: string }>(accessToken);
      const latestUser = await getUserProfile(decoded.sub);

      if (
        requestId === profileRequestId.current &&
        localStorage.getItem("accessToken") === accessToken
      ) {
        setUser(latestUser);
      }
    } catch (error) {
      if (
        requestId === profileRequestId.current &&
        !localStorage.getItem("accessToken")
      ) {
        setUser(null);
      }

      console.error("사용자 정보 조회 실패:", error);
    }
  }, []);

  const scheduleProfileRefresh = useCallback(() => {
    if (profileRefreshTimer.current !== null) {
      return;
    }

    profileRefreshTimer.current = window.setTimeout(() => {
      profileRefreshTimer.current = null;
      void refreshUserProfile();
    }, 300);
  }, [refreshUserProfile]);

  useEffect(() => {
    const timerId = window.setTimeout(() => {
      void refreshUserProfile();
    }, 0);

    return () => {
      window.clearTimeout(timerId);

      if (profileRefreshTimer.current !== null) {
        window.clearTimeout(profileRefreshTimer.current);
        profileRefreshTimer.current = null;
      }

      profileRequestId.current += 1;
    };
  }, [refreshUserProfile]);
  useEffect(() => {
    fetch("/api/products?status=all")
    .then((response) => response.json())
    .then((data: Product[]) => {
      const foundProduct = data.find((item) => item.id === id);
      
      if (foundProduct) {
        setProduct(foundProduct);
        setCurrentPrice(foundProduct.currentPrice);
        setEndAt(foundProduct.endAt);

        // 이미 마감된 상품을 열었을 때도 입찰을 막고 결과를 보여준다. 예전에는 실시간 마감 이벤트를
        // 받아야만 막혔고, 새로고침했거나 이벤트를 놓치면 마감된 상품에서도 입찰할 수 있는 것처럼 보였다 (이슈 #153).
        if (foundProduct.status === "CANCELLED") {
          setIsCancelled(true);
        } else if (CLOSED_STATUSES.includes(foundProduct.status)) {
          setIsAuctionClosed(true);
          setWinnerId(foundProduct.winnerId);
          setWinnerUsername(foundProduct.winnerUsername ?? null);
        }
      } else {
        setProduct(null);
      }
    })
    .catch((error) => {
      console.error("상품 상세 조회 실패:", error);
    });
  }, [id]);

  const productId = product?.id;

  useEffect(() => {
    if (!productId) {
      return;
    }

    let cancelled = false;

    getBidHistory(productId)
      .then((history) => {
        if (!cancelled) {
          setBidHistory(history);
        }
      })
      .catch((error) => {
        console.error("입찰 내역 조회 실패:", error);
      });

    return () => {
      cancelled = true;
    };
  }, [productId]);

  useEffect(() => {
    if (!productId) {
      return;
    }

    const connection = connectAuctionSocket(productId, {
      onEvent: (data) => {
        if (data.event === "bid_placed") {
          setCurrentPrice(data.current_price);
          scheduleProfileRefresh();
          
          getBidHistory(productId)
            .then(setBidHistory)
            .catch((error) => {
              console.error("입찰 후 최고 입찰자 갱신 실패:", error);
            });
        }

        if (data.event === "bid_cancelled") {
          scheduleProfileRefresh();
        }

        if (data.event === "auction_cancelled") {
          setIsCancelled(true);
        }

        if (data.event === "auction_extended") {
          setEndAt(data.new_end_at);
        }

        if (data.event === "auction_closed") {
          setIsAuctionClosed(true);
          setWinnerId(data.winner_id);
          setFinalPrice(data.final_price);
          scheduleProfileRefresh();

          // 이벤트에는 낙찰자 ID만 있어서, 닉네임은 상품 정보를 다시 읽어서 채운다 (엔진 이슈 #148).
          // 실패하거나 닉네임이 아직 없으면 ID로 대신 보여준다.
          fetch(`/api/products/${productId}`)
            .then((response) => response.json())
            .then((latest: Product) => {
              setWinnerUsername(latest.winnerUsername ?? null);
            })
            .catch((error) => {
              console.error("낙찰자 닉네임 조회 실패:", error);
            });
        }
      },
      // 끊겨 있던 동안 놓친 입찰을 REST로 다시 불러와 현재가를 맞춘다
      onReconnect: () => {
        scheduleProfileRefresh();

        fetch(`/api/products/${productId}`)
          .then((response) => response.json())
          .then((latest: Product) => {
            setCurrentPrice(latest.currentPrice);
          })
          .catch((error) => {
            console.error("재연결 후 상품 정보 동기화 실패:", error);
          });
      },
      onStatusChange: setSocketStatus,
    });

    return () => {
      connection.close();
    };
  }, [productId, scheduleProfileRefresh]);

  useEffect(() => {
    if (!productId || !localStorage.getItem("accessToken")) {
      return;
    }

    const connection = connectLobbySocket({
      onEvent: (data) => {
        if (
          data.event === "bid_placed" ||
          data.event === "bid_cancelled" ||
          data.event === "auction_closed"
        ) {
          if (data.product_id !== productId) {
            scheduleProfileRefresh();
          }
        }
      },
      onReconnect: () => {
        scheduleProfileRefresh();
      },
    });

    return () => {
      connection.close();
    };
  }, [productId, scheduleProfileRefresh]);

  // 마감 시각이 지나면(엔진이 마감 처리하기 전이어도) 입찰을 막는다 (이슈 #153). 엔진도 마감 시각이 지난 입찰은
  // 409로 거절하지만, 화면에서 먼저 알려주는 것이다. isAuctionClosed처럼 한번 켜지면 안 꺼지는 값이 아니라
  // 현재 시각과 endAt에서 매번 계산하므로, 안티 스나이핑으로 마감이 연장되면(endAt이 늘어나면) 다시 입찰할 수 있다.
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const intervalId = window.setInterval(() => setNow(Date.now()), 1000);

    return () => {
      window.clearInterval(intervalId);
    };
  }, []);

  const isEndTimePassed = endAt !== "" && new Date(endAt).getTime() <= now;
  const isBiddingClosed = isAuctionClosed || isEndTimePassed || isCancelled;

  const remainingSeconds = endAt
    ? Math.max(0, Math.ceil((new Date(endAt).getTime() - now) / 1000))
    : 0;

  const hours = Math.floor(remainingSeconds / 3600);
  const minutes = Math.floor((remainingSeconds % 3600) / 60);
  const seconds = remainingSeconds % 60;

  const countdown = [hours, minutes, seconds]
    .map((value) => String(value).padStart(2, "0"))
    .join(":");

  const highestBid = bidHistory
    .filter((bid) => bid.isValid)
    .reduce<BidHistoryResponse | null>(
      (highest, bid) =>
        highest === null || bid.amount > highest.amount ? bid : highest,
      null
    );

  // 최종 낙찰가: 경매 종료 이벤트가 알려준 값이 가장 정확하다. 이미 마감된 상품을 열었을 때는 이벤트가 없어서
  // 낙찰자의 가장 높은 유효 입찰가를 입찰 이력에서 찾아 보여주고(이력이 오기 전에는 현재가), 마감 처리는
  // 상품의 현재가를 낙찰가로 바꾸지 않아서 크레딧 부족으로 차순위가 낙찰된 경우 현재가는 낙찰가와 다를 수 있다.
  const winnerTopBidAmount = bidHistory
    .filter((bid) => bid.isValid && bid.bidderId === winnerId)
    .reduce<number | null>(
      (max, bid) => (max === null || bid.amount > max ? bid.amount : max),
      null
    );
  const displayFinalPrice = finalPrice ?? winnerTopBidAmount ?? currentPrice;

  if (!product) {
    return (
      <main className="page-container">
        <h1>상품을 찾을 수 없습니다.</h1>
        <Link to="/">상품 목록으로 돌아가기</Link>
      </main>
    );
  }

  const handleBid = async () => {
  const amount = Number(bidAmount);

  setBidMessage("");

  if (!amount) {
    setBidMessage("입찰 금액을 입력해주세요.");
    return;
  }

  const minimumBid = currentPrice + product.bidUnit;

  if (amount < minimumBid) {
    setBidMessage(
      `최소 입찰 금액은 ${minimumBid.toLocaleString()}원입니다.`
    );
    return;
  }

  try {
    setIsBidding(true);

    const result = await placeBid({
      productId: product.id,
      amount,
    });

    if (result.isValid) {
      setBidMessage(`${amount.toLocaleString()}원 입찰에 성공했습니다.`);
      setBidAmount("");
      void refreshUserProfile();
    } else if (result.reason === "INSUFFICIENT_CREDIT") {
      // 금액은 충분하지만 사용 가능 크레딧(보유 - 다른 진행중 경매에 묶인 금액)을 넘는 입찰이다.
      // "더 높은 금액으로"라는 안내가 나가면 반대가 되므로 따로 안내한다.
      setBidMessage(
        "사용 가능한 크레딧이 부족합니다. 다른 경매에 묶인 크레딧을 확인해 주세요."
      );
    } else {
      if (result.reason === "INSUFFICIENT_CREDIT") {
        setBidMessage("사용 가능한 크레딧이 부족하여 입찰할 수 없습니다.");
      } else {
        const minNextBid =
          result.minNextBid ?? currentPrice + product.bidUnit;

        setBidMessage(
          `현재 최소 입찰가는 ${minNextBid.toLocaleString()}원입니다. 더 높은 금액으로 입찰해 주세요.`
        );
      }
    }
  } catch (error) {
    console.error("입찰 요청 실패:", error);
    setBidMessage(
      error instanceof Error
        ? error.message
        : "입찰 요청 중 오류가 발생했습니다."
    );
  } finally {
    setIsBidding(false);
  }
  };

  // 판매자 본인이고, 아직 끝나지 않았고, 유효 입찰이 없을 때만 취소 버튼을 보여준다.
  // 엔진도 같은 조건을 다시 확인하므로(이슈 #183) 화면 판단이 늦어도 잘못 취소되지는 않는다.
  const canCancel =
    user !== null &&
    user.id === product.sellerId &&
    !isBiddingClosed &&
    highestBid === null;

  const handleCancel = async () => {
    if (!window.confirm("이 경매를 취소할까요? 취소한 경매는 되돌릴 수 없습니다.")) {
      return;
    }

    setCancelMessage("");

    try {
      setIsCancelling(true);
      await cancelAuction(product.id);
      setIsCancelled(true);
    } catch (error) {
      console.error("경매 취소 실패:", error);
      setCancelMessage(
        error instanceof Error
          ? error.message
          : "경매 취소 중 오류가 발생했습니다."
      );
    } finally {
      setIsCancelling(false);
    }
  };

  return (
    <main className="page-container">
      <div className="product-detail">
        <div className="product-detail-image">
          {product.imageUrl ? (
            <img
              src={product.imageUrl}
              alt={product.title}
              style={{
                width: "100%",
                height: "100%",
                objectFit: "contain",
              }}
            />
          ) : (
            <span>상품 이미지 없음</span>
          )}
        </div>

        <div className="product-detail-info">
          <h1>{product.title}</h1>
          <p>{product.description}</p>
          <p>판매자: {product.sellerUsername ?? "정보 없음"}</p>

          {socketStatus === "reconnecting" && (
            <p className="socket-status">
              실시간 연결이 끊겨 다시 연결하는 중입니다...
            </p>
          )}
          {socketStatus === "closed" && (
            <p className="socket-status">
              실시간 연결이 끊겼습니다. 새로고침해 주세요.
            </p>
          )}

          <div className="bid-info">
            <p>
              시작가: {product.startingPrice.toLocaleString()}원
            </p>

            <p>
              현재가: <strong>{currentPrice.toLocaleString()}원</strong>
            </p>

            <p>
              최고 입찰자:{" "}
              <strong>
                {highestBid
                  ? (highestBid.bidderUsername ?? highestBid.bidderId)
                  : "아직 입찰자가 없습니다."}
              </strong>
            </p>

            <p>
              최소 입찰 단위: {product.bidUnit.toLocaleString()}원
            </p>
            <p>
              남은 시간:{" "}
              <strong>
                {isCancelled
                  ? "경매 취소"
                  : isBiddingClosed
                    ? "경매 종료"
                    : countdown}
              </strong>
            </p>
            <p>
              마감 시각:{" "}
              <strong>
                {endAt ? new Date(endAt).toLocaleString("ko-KR") : "정보 없음"}
              </strong>
            </p>
            {isAuctionClosed && (
              <div className="auction-result">
                <h3>경매 종료</h3>

                {winnerId !== null ? (
                  <>
                    {winnerUsername ? (
                      <p>낙찰자: {winnerUsername}</p>
                    ) : (
                      <p>낙찰자 ID: {winnerId}</p>
                    )}
                    <p>
                      최종 낙찰가: {displayFinalPrice.toLocaleString()}원
                    </p>
                  </>
                ) : (
                  <p>낙찰자가 없어 유찰되었습니다.</p>
                )}
              </div>
            )}
            {isCancelled && (
              <div className="auction-result">
                <h3>경매 취소</h3>
                <p>판매자가 취소한 경매입니다.</p>
              </div>
            )}
            {!isAuctionClosed && !isCancelled && isEndTimePassed && (
              <p>경매가 마감되었습니다. 낙찰 결과를 집계하는 중입니다.</p>
            )}
          </div>
          {user && (
            <p>
              <strong>사용 가능 크레딧: </strong>
              {user.availableCredit !== undefined
                ? `${user.availableCredit.toLocaleString()}원`
                : "정보를 불러올 수 없습니다."}
            </p>
          )}
          <input
            type="number"
            value={bidAmount}
            onChange={(e) => setBidAmount(e.target.value)}
            placeholder="입찰 금액을 입력하세요"
          />
          <button
            type="button"
            onClick={() => {
              const minimumBid = currentPrice + product.bidUnit;
              const currentAmount = Number(bidAmount);

              const nextAmount =
                currentAmount < minimumBid
                  ? minimumBid
                  : currentAmount + product.bidUnit;

              setBidAmount(String(nextAmount));
            }}
            disabled={isBidding || isBiddingClosed}
          >
            +{product.bidUnit.toLocaleString()}원
          </button>
          <button
            type="button"
            onClick={handleBid}
            disabled={isBidding || isBiddingClosed}
          >
            {isCancelled
              ? "경매 취소"
              : isBiddingClosed
              ? "경매 종료"
              : isBidding
                ? "입찰 처리 중..."
                : "입찰하기"}
          </button>
          {bidMessage && (
            <p className="bid-message">
              {bidMessage}
            </p>
          )}
          {canCancel && (
            <button
              type="button"
              onClick={handleCancel}
              disabled={isCancelling}
            >
              {isCancelling ? "취소 처리 중..." : "경매 취소"}
            </button>
          )}
          {cancelMessage && (
            <p className="bid-message">
              {cancelMessage}
            </p>
          )}
        </div>
      </div>
    </main>
  );
}

export default ProductDetail;