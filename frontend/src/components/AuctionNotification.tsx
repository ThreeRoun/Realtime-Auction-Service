import { useEffect, useRef, useState } from "react";
import { jwtDecode } from "jwt-decode";
import { getMyProducts, getProductById } from "../api/products";
import { connectLobbySocket } from "../websocket/auctionSocket";

interface JwtPayload {
  sub: string;
}

function getLoggedInUserId(): string | null {
  const token = localStorage.getItem("accessToken");

  if (!token) {
    return null;
  }

  try {
    const decoded = jwtDecode<JwtPayload>(token);
    return decoded.sub;
  } catch {
    return null;
  }
}

function AuctionNotification() {
  const [notification, setNotification] = useState<{
    productId: string;
    finalPrice: number | null;
    isSeller: boolean;
    isParticipant: boolean;
    isWinner: boolean;
  } | null>(null);
  const processedAuctionIds = useRef<Set<string>>(new Set());
  useEffect(() => {
    const connection = connectLobbySocket({
      onEvent: (data) => {
        if (data.event !== "auction_closed") {
          return;
        }

        const productId = String(data.product_id);

        const userId = getLoggedInUserId();

        if (!userId) {
          return;
        }

        if (processedAuctionIds.current.has(productId)) {
          return;
        }

        processedAuctionIds.current.add(productId);

        const checkRelatedAuction = async () => {
          try {
            const results = await Promise.allSettled([
              getMyProducts(userId, "registered"),
              getMyProducts(userId, "participated"),
              getProductById(productId),
            ] as const);

        const [registeredResult, participatedResult, productResult] = results;

if (results.every((result) => result.status === "rejected")) {
  throw new Error("모든 경매 정보 조회에 실패했습니다.");
}

const registered =
  registeredResult.status === "fulfilled"
    ? registeredResult.value
    : [];

const participated =
  participatedResult.status === "fulfilled"
    ? participatedResult.value
    : [];

const latestProduct =
  productResult.status === "fulfilled"
    ? productResult.value
    : null;

            const isSeller = registered.some(
              (product) => String(product.id) === String(data.product_id)
            );

            const isParticipant = participated.some(
              (product) => String(product.id) === String(data.product_id)
            );

            const isWinner = latestProduct?.winnerId === userId;

            if (isSeller || isParticipant || isWinner) {
              setNotification({
                productId: data.product_id,
                finalPrice: data.final_price,
                isSeller,
                isParticipant,
                isWinner,
              });
            } else if (results.some((result) => result.status === "rejected")) {
              processedAuctionIds.current.delete(productId);
            }
          } catch (error) {
            processedAuctionIds.current.delete(productId);
            console.error("경매 알림 대상 확인 실패:", error);
          }
        };

        void checkRelatedAuction();
      },
    });

    return () => {
      connection.close();
    };
  }, []);

  if (!notification) {
    return null;
  }

  return (
    <div
      role="alertdialog"
      aria-labelledby="auction-notification-title"
      aria-describedby="auction-notification-description"
      style={{
        position: "fixed",
        top: "80px",
        right: "24px",
        zIndex: 1000,
        width: "320px",
        padding: "20px",
        borderRadius: "12px",
        backgroundColor: "#ffffff",
        color: "#222222",
        boxShadow: "0 4px 20px rgba(0, 0, 0, 0.2)",
      }}
    >
      <h3 id="auction-notification-title">🔔 경매 종료 알림</h3>

      <p id="auction-notification-description">
        {notification.isWinner
          ? "🎉 축하합니다! 경매에 낙찰되었습니다."
          : notification.isSeller
            ? "등록하신 경매가 종료되었습니다."
            : "참여하신 경매가 종료되었습니다."}
      </p>

      <p>
        <strong>최종 낙찰가:</strong>{" "}
        {notification.finalPrice !== null
          ? `${notification.finalPrice.toLocaleString()}원`
          : "낙찰자 없음"}
      </p>

      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          marginTop: "16px",
        }}
      >
        <a href={`/products/${notification.productId}`}>
          경매 확인하기
        </a>

        <button
          type="button"
          onClick={() => setNotification(null)}
        >
          닫기
        </button>
      </div>
    </div>
  );
}

export default AuctionNotification;