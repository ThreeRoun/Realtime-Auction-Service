import ProductCard from "../components/ProductCard";
import { products } from "../data/products";

function ProductList() {
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