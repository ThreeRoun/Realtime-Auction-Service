import { Link } from "react-router-dom";
import type { Product } from "../data/products";

interface ProductCardProps {
  product: Product;
}

function ProductCard({ product }: ProductCardProps) {
  return (
    <div className="product-card">
      <div className="product-image">상품 이미지</div>

      <div className="product-card-content">
        <h3>{product.title}</h3>
        <p>{product.description}</p>

        <div className="product-price">
          현재가: {product.currentPrice.toLocaleString()}원
        </div>

        <div className="product-end-time">
          마감: {product.endAt}
        </div>

        <Link to={`/products/${product.id}`} className="detail-button">
          경매 참여
        </Link>
      </div>
    </div>
  );
}

export default ProductCard;