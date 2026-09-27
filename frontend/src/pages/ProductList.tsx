import { useEffect, useState } from "react";
import ProductCard from "../components/ProductCard";
import type { Product } from "../data/products";

function ProductList() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
  fetch("/api/products")
    .then((response) => response.json())
    .then((data: Product[]) => {
      setProducts(data);
    })
    .catch((error) => {
      console.error("상품 목록 조회 실패:", error);
    })
    .finally(() => {
      setLoading(false);
    });
  }, []);

  if (loading) {
    return (
      <main className="page-container">
        <p>상품 목록을 불러오는 중입니다...</p>
      </main>
    );
  }

  return (
    <main className="page-container">
      <section className="page-header">
        <h1>진행 중인 경매</h1>
        <p>현재 진행 중인 경매 상품을 확인하고 입찰에 참여해보세요.</p>
      </section>

      <section className="product-grid">
        {products.map((product) => (
          <ProductCard key={product.id} product={product} />
        ))}
      </section>
    </main>
  );
}

export default ProductList;