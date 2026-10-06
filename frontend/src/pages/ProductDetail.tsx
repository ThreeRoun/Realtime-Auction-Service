import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Product } from "../data/products";
import { placeBid } from "../api/bids";
import {
  connectAuctionSocket,
  type BidPlacedEvent,
} from "../websocket/auctionSocket";

function ProductDetail() {
  const { id } = useParams();
  const [product, setProduct] = useState<Product | null>(null);
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

  useEffect(() => {
    if (!product) {
      return;
    }

    const socket = connectAuctionSocket(product.id);

    socket.onmessage = (event) => {
      const data: BidPlacedEvent = JSON.parse(event.data);

      if (data.event === "bid_placed") {
        setCurrentPrice(data.current_price);
      }
    };

    return () => {
      socket.close();
    };
  }, [product]);

  const [currentPrice, setCurrentPrice] = useState(0);
  const [bidAmount, setBidAmount] = useState("");
  const [isBidding] = useState(false);
  const [bidMessage, setBidMessage] = useState("");

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
    const result = await placeBid({
      productId: product.id,
      bidderId: "e0846e26-aa29-40f5-ae37-bd4f21e59958",
      amount,
    });

    if (result.isValid) {
      setBidMessage(`${amount.toLocaleString()}원 입찰에 성공했습니다.`);
    } else {
      setBidMessage("유효하지 않은 입찰입니다.");
    }
  } catch (error) {
    console.error("입찰 요청 실패:", error);
    setBidMessage("입찰 요청 중 오류가 발생했습니다.");
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