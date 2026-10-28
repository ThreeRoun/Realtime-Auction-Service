import { useCallback, useEffect, useState } from "react";
import ProductCard from "../components/ProductCard";
import type { Product } from "../data/products";
import {
  connectLobbySocket,
  type LobbyEvent,
  type SocketStatus,
} from "../websocket/auctionSocket";

function ProductList() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [socketStatus, setSocketStatus] = useState<SocketStatus>("connecting");

  const loadProducts = useCallback(() => {
    return fetch("/api/products")
      .then((response) => response.json())
      .then((data: Product[]) => {
        setProducts(data);
      })
      .catch((error) => {
        console.error("상품 목록 조회 실패:", error);
      });
  }, []);

  useEffect(() => {
    loadProducts().finally(() => {
      setLoading(false);
    });
  }, [loadProducts]);

  // 로비에 접속해 모든 상품의 실시간 이벤트를 목록에 반영한다
  useEffect(() => {
    const handleEvent = (data: LobbyEvent) => {
      switch (data.event) {
        case "bid_placed":
          setProducts((prev) =>
            prev.map((p) =>
              p.id === data.product_id
                ? { ...p, currentPrice: data.current_price }
                : p
            )
          );
          break;
        case "auction_extended":
          setProducts((prev) =>
            prev.map((p) =>
              p.id === data.product_id ? { ...p, endAt: data.new_end_at } : p
            )
          );
          break;
        case "auction_closed":
          // 진행 중인 경매 목록이므로 마감된 상품은 목록에서 뺀다
          setProducts((prev) => prev.filter((p) => p.id !== data.product_id));
          break;
      }
    };

    const connection = connectLobbySocket({
      onEvent: handleEvent,
      // 끊겨 있던 동안 놓친 변경을 반영하기 위해 목록을 다시 불러온다
      onReconnect: () => {
        loadProducts();
      },
      onStatusChange: setSocketStatus,
    });

    return () => {
      connection.close();
    };
  }, [loadProducts]);

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