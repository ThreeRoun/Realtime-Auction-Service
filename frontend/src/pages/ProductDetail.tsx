import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Product } from "../data/products";
import { placeBid } from "../api/bids";
import {
  connectAuctionSocket,
  type SocketStatus,
} from "../websocket/auctionSocket";

function ProductDetail() {
  const { id } = useParams();
  const [product, setProduct] = useState<Product | null>(null);
  const [currentPrice, setCurrentPrice] = useState(0);
  const [bidAmount, setBidAmount] = useState("");
  const [isBidding, setIsBidding] = useState(false);
  const [bidMessage, setBidMessage] = useState("");
  const [socketStatus, setSocketStatus] = useState<SocketStatus>("connecting");
  useEffect(() => {
    fetch("/api/products?status=all")
    .then((response) => response.json())
    .then((data: Product[]) => {
      const foundProduct = data.find((item) => item.id === id);
      
      if (foundProduct) {
        setProduct(foundProduct);
        setCurrentPrice(foundProduct.currentPrice);
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

    const connection = connectAuctionSocket(productId, {
      onEvent: (data) => {
        if (data.event === "bid_placed") {
          setCurrentPrice(data.current_price);
        }
      },
      // 끊겨 있던 동안 놓친 입찰을 REST로 다시 불러와 현재가를 맞춘다
      onReconnect: () => {
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
  }, [productId]);


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
    } else {
      setBidMessage("유효하지 않은 입찰입니다.");
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

  return (
    <main className="page-container">
      <div className="product-detail">
        <div className="product-detail-image">
          상품 이미지
        </div>

        <div className="product-detail-info">
          <h1>{product.title}</h1>
          <p>{product.description}</p>

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
              최소 입찰 단위: {product.bidUnit.toLocaleString()}원
            </p>

            <p>마감 시각: {product.endAt}</p>
          </div>
          <input
            type="number"
            value={bidAmount}
            onChange={(e) => setBidAmount(e.target.value)}
            placeholder="입찰 금액을 입력하세요"
          />
          <button
            type="button"
            onClick={handleBid}
            disabled={isBidding}
          >
            {isBidding ? "입찰 처리 중..." : "입찰하기"}
          </button>
          {bidMessage && (
            <p className="bid-message">
              {bidMessage}
            </p>
          )}
        </div>
      </div>
    </main>
  );
}

export default ProductDetail;