import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Product } from "../data/products";

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

  const [currentPrice, setCurrentPrice] = useState(
    product?.currentPrice ?? 0
  );

  if (!product) {
    return (
      <main className="page-container">
        <h1>상품을 찾을 수 없습니다.</h1>
        <Link to="/">상품 목록으로 돌아가기</Link>
      </main>
    );
  }

  const handleBid = () => {
    setCurrentPrice((prevPrice) => prevPrice + product.bidUnit);
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