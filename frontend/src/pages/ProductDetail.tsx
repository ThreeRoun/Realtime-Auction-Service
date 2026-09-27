import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Product } from "../data/products";

function ProductDetail() {
  const { id } = useParams();
  const [product, setProduct] = useState<Product | null>(null);
  useEffect(() => {
    fetch("/api/products?status=all")
    .then((response) => response.json())
    .then((data: Product[]) => {
      const foundProduct = data.find((item) => item.id === id);
      setProduct(foundProduct ?? null);
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

          <button type="button" onClick={handleBid}>
            {product.bidUnit.toLocaleString()}원 입찰
          </button>
        </div>
      </div>
    </main>
  );
}

export default ProductDetail;