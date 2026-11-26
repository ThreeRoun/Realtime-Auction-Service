import { Link } from "react-router-dom";
import type { Product } from "../data/products";
import { getStatusLabel, LIVE_STATUSES } from "../data/productStatus";

interface MyAuctionCardProps {
  product: Product;
  // 남은 시간 계산 기준 시각. 카드마다 타이머를 두지 않고 마이페이지의 1초 타이머 하나를 공유한다.
  now: number;
  // 낙찰받은 경매는 "낙찰가", 나머지는 "현재가"
  priceLabel?: string;
}

function formatRemainingTime(endAt: string, now: number): string {
  const remainingSeconds = Math.max(
    0,
    Math.ceil((new Date(endAt).getTime() - now) / 1000)
  );

  const hours = Math.floor(remainingSeconds / 3600);
  const minutes = Math.floor((remainingSeconds % 3600) / 60);
  const seconds = remainingSeconds % 60;

  return [hours, minutes, seconds]
    .map((value) => String(value).padStart(2, "0"))
    .join(":");
}

function MyAuctionCard({ product, now, priceLabel = "현재가" }: MyAuctionCardProps) {
  const isLive = LIVE_STATUSES.includes(product.status);

  return (
    <li className="auction-card">
      <Link to={`/products/${product.id}`}>
        {product.imageUrl ? (
          <img
            src={product.imageUrl}
            alt={product.title}
            className="auction-card-image"
          />
        ) : (
          <div className="auction-card-image-placeholder">
            상품 이미지 없음
          </div>
        )}

        <h3>{product.title}</h3>
      </Link>

      <p>
        {priceLabel}: <strong>{product.currentPrice.toLocaleString()}원</strong>
      </p>

      <p>
        상태: <strong>{getStatusLabel(product.status)}</strong>
      </p>

      {product.status === "PENDING" && (
        <p>
          시작 시각: {new Date(product.startAt).toLocaleString("ko-KR")}
        </p>
      )}

      {isLive && (
        <p>
          남은 시간: <strong>{formatRemainingTime(product.endAt, now)}</strong>
        </p>
      )}

      <p>
        마감 시각: {new Date(product.endAt).toLocaleString("ko-KR")}
      </p>
    </li>
  );
}

export default MyAuctionCard;
