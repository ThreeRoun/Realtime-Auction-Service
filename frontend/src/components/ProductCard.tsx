import { Link } from "react-router-dom";
import type { Product } from "../data/products";
import AuctionCountdown from "./AuctionCountdown";
import { LIVE_STATUSES, getStatusLabel } from "../data/productStatus";

interface ProductCardProps {
  product: Product;
}

function ProductCard({ product }: ProductCardProps) {
  const isLive = LIVE_STATUSES.includes(product.status);

  return (
    <div className="product-card">
      <div className="product-image">
        {product.imageUrl ? (
          <img
            src={product.imageUrl}
            alt={product.title}
            style={{
              width: "100%",
              height: "100%",
              objectFit: "cover",
            }}
          />
        ) : (
          <span>상품 이미지 없음</span>
        )}
      </div>

      <div className="product-card-content">
        <h3>{product.title}</h3>
        <p>{product.description}</p>

        <div className="product-status">
          상태: {getStatusLabel(product.status)}
        </div>

        <div className="product-price">
          {isLive ? "현재가" : "종료 시 가격"}:{" "}
          {product.currentPrice.toLocaleString()}원
        </div>

        <div className="product-end-time">
          <div>시작: {product.startAt || "정보 없음"}</div>
          <div>마감: {product.endAt}</div>
        </div>

        {isLive && <AuctionCountdown endAt={product.endAt} />}

        <Link to={`/products/${product.id}`} className="detail-button">
          {isLive ? "경매 참여" : "상세 보기"}
        </Link>
      </div>
    </div>
  );
}

export default ProductCard;
