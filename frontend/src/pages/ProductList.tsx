import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import ProductCard from "../components/ProductCard";
import AuctionTabs, { type AuctionTab } from "../components/AuctionTabs";
import type { Product } from "../data/products";
import {
  connectLobbySocket,
  type LobbyEvent,
  type SocketStatus,
} from "../websocket/auctionSocket";

function ProductList() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loadedTab, setLoadedTab] = useState<AuctionTab | null>(null);
  const [error, setError] = useState(false);
  const [searchParams, setSearchParams] = useSearchParams();

  const activeTab: AuctionTab =
  searchParams.get("tab") === "ended" ? "ended" : "active";

  const loading = loadedTab !== activeTab;

  const setActiveTab = (tab: AuctionTab) => {
    if (tab === activeTab) return;

    const nextParams = new URLSearchParams(searchParams);

    if (tab === "ended") {
      nextParams.set("tab", "ended");
    } else {
      nextParams.delete("tab");
    }

    setSearchParams(nextParams);
  };
  const [socketStatus, setSocketStatus] =
    useState<SocketStatus>("connecting");

  const loadProducts = useCallback((signal?: AbortSignal) => {
    const url =
      activeTab === "active"
        ? "/api/products"
        : "/api/products?status=ended";

    return fetch(url, { signal })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("상품 목록 조회 실패");
        }

        const data: Product[] = await response.json();

        if (!signal?.aborted) {
          setProducts(data);
          setError(false);
        }
      })
      .catch((error) => {
        if (!signal?.aborted) {
          console.error("상품 목록 조회 실패:", error);
          setError(true);
        }
      });
  }, [activeTab]);

  useEffect(() => {
    const controller = new AbortController();

    loadProducts(controller.signal).finally(() => {
      if (!controller.signal.aborted) {
        setLoadedTab(activeTab);
      }
    });

    return () => {
      controller.abort();
    };
  }, [activeTab, loadProducts]);

  // 실시간 경매 이벤트를 현재 목록에 반영한다.
  useEffect(() => {
    const controller = new AbortController();

    const handleEvent = (data: LobbyEvent) => {
      switch (data.event) {
        case "bid_placed":
        case "bid_cancelled":
          if (activeTab === "active") {
            setProducts((prev) =>
              prev.map((p) =>
                p.id === data.product_id
                  ? { ...p, currentPrice: data.current_price }
                  : p
              )
            );
          }
          break;

        case "auction_extended":
          if (activeTab === "active") {
            setProducts((prev) =>
              prev.map((p) =>
                p.id === data.product_id
                  ? { ...p, endAt: data.new_end_at, status: "EXTENDED" }
                  : p
              )
            );
          }
          break;

        case "auction_closed":
        case "auction_cancelled":
          // 종료 및 취소된 경매를 현재 탭에 맞게 다시 조회한다.
          loadProducts(controller.signal);
          break;
      }
    };

    const connection = connectLobbySocket({
      onEvent: handleEvent,
      onReconnect: () => {
        loadProducts(controller.signal);
      },
      onStatusChange: setSocketStatus,
    });

    return () => {
      controller.abort();
      connection.close();
    };
  }, [activeTab, loadProducts]);

  return (
    <main className="page-container">
      <section className="page-header">
        <h1>경매 상품 목록</h1>
        <p>진행 중이거나 종료된 경매 상품을 확인해보세요.</p>

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

      <AuctionTabs
        activeTab={activeTab}
        onTabChange={setActiveTab}
      />

      {loading ? (
        <p>상품 목록을 불러오는 중입니다...</p>
      ) : error ? (
        <p>상품 목록을 불러오지 못했습니다.</p>
      ) : products.length === 0 ? (
        <p>
          {activeTab === "active"
            ? "현재 진행 중인 경매가 없습니다."
            : "종료된 경매가 없습니다."}
        </p>
      ) : (
        <section className="product-grid">
          {products.map((product) => (
            <ProductCard key={product.id} product={product} />
          ))}
        </section>
      )}
    </main>
  );
}

export default ProductList;
