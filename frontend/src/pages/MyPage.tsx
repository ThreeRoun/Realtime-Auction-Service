import { useEffect, useState } from "react";
import { jwtDecode } from "jwt-decode";
import { getUserProfile, type UserProfile } from "../api/auth";
import { Link } from "react-router-dom";
import { getMyProducts } from "../api/products";
import type { Product } from "../data/products";

interface JwtPayload {
  sub: string;
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

function MyPage() {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      setNow(Date.now());
    }, 1000);

    return () => window.clearInterval(intervalId);
  }, []);
  const [user, setUser] = useState<UserProfile | null>(null);
  const [registeredAuctions, setRegisteredAuctions] = useState<Product[]>([]);
  const [participatedAuctions, setParticipatedAuctions] = useState<Product[]>([]);
  const [wonAuctions, setWonAuctions] = useState<Product[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [auctionsLoading, setAuctionsLoading] = useState(true);
  const [auctionsError, setAuctionsError] = useState("");

  useEffect(() => {
    const loadProfile = async () => {
      try {
        const token = localStorage.getItem("accessToken");

        if (!token) {
          throw new Error("로그인이 필요합니다.");
        }

        const decoded = jwtDecode<JwtPayload>(token);
        const profile = await getUserProfile(decoded.sub);

        setUser(profile);

        const [registered, participated] = await Promise.all([
          getMyProducts(decoded.sub, "registered"),
          getMyProducts(decoded.sub, "participated"),
        ]);

        setRegisteredAuctions(registered);
        setParticipatedAuctions(participated);
        const won = participated.filter(
          (product) => product.winnerId === decoded.sub
        );

        setWonAuctions(won);
      } catch (err) {
        const message =
          err instanceof Error
            ? err.message
            : "정보를 불러오지 못했습니다.";

        setError(message);
        setAuctionsError(message);
      } finally {
        setLoading(false);
        setAuctionsLoading(false);
      }
    };

    void loadProfile();
  }, []);

  const activeAuctions = participatedAuctions.filter(
    (product) => product.status === "IN_PROGRESS"
  );

  const endedAuctions = participatedAuctions.filter(
    (product) =>
      ["SOLD", "UNSOLD", "COMPLETED"].includes(product.status)
  );

  return (
    <main className="page-container">
      <section className="page-header">
        <h1>마이페이지</h1>
        <p>내 정보와 경매 참여 현황을 확인할 수 있습니다.</p>
      </section>

      <section className="mypage-profile">
        <h2>내 정보</h2>

        {loading && <p>사용자 정보를 불러오는 중입니다.</p>}
        {error && <p>{error}</p>}

        {user && (
          <div className="profile-info">
            <p>
              <strong>닉네임:</strong> {user.username}
            </p>
            <p>
              <strong>이메일:</strong> {user.email}
            </p>
            <p>
              <strong>보유 크레딧:</strong>{" "}
              {user.credit.toLocaleString()}원
            </p>
            <p>
              <strong>사용 가능 크레딧:</strong>{" "}
              {user.availableCredit !== undefined
                ? `${user.availableCredit.toLocaleString()}원`
                : "정보를 불러올 수 없습니다."}
            </p>
          </div>
        )}
      </section>

      <section className="mypage-auctions">
  <h2>참여 중인 경매</h2>

  {auctionsLoading ? (
    <p>경매 목록을 불러오는 중입니다.</p>
  ) : auctionsError ? (
    <p role="alert">{auctionsError}</p>
  ) : activeAuctions.length === 0 ? (
    <div className="mypage-empty">
      현재 참여 중인 경매가 없습니다.
    </div>
  ) : (
    <ul>
      {activeAuctions.map((product) => (
        <li key={product.id} className="auction-card">
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
            현재가: <strong>{product.currentPrice.toLocaleString()}원</strong>
          </p>

          <p>상태: 진행 중</p>

          <p>
            남은 시간:{" "}
            <strong>
              {formatRemainingTime(product.endAt, now)}
            </strong>
          </p>

          <p>
            마감 시각: {new Date(product.endAt).toLocaleString("ko-KR")}
          </p>
        </li>
      ))}
    </ul>
  )}
</section>

<section className="mypage-auctions">
  <h2>종료된 경매</h2>

  {auctionsLoading ? (
    <p>경매 목록을 불러오는 중입니다.</p>
  ) : auctionsError ? (
    <p role="alert">{auctionsError}</p>
  ) : endedAuctions.length === 0 ? (
    <div className="mypage-empty">
      종료된 경매가 없습니다.
    </div>
  ) : (
    <ul>
      {endedAuctions.map((product) => (
        <li key={product.id} className="auction-card">
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
            현재가: <strong>{product.currentPrice.toLocaleString()}원</strong>
          </p>

          <p>
            상태:{" "}
            <strong>
              {product.status === "SOLD" || product.status === "COMPLETED"
                ? "경매 종료"
                : product.status === "UNSOLD"
                  ? "유찰"
                  : "종료"}
            </strong>
          </p>

          <p>
            마감 시각: {new Date(product.endAt).toLocaleString("ko-KR")}
          </p>
        </li>
      ))}
    </ul>
  )}
</section>
      <section className="mypage-auctions">
        <h2>내가 등록한 경매</h2>

        {auctionsLoading ? (
          <p>경매 목록을 불러오는 중입니다.</p>
        ) : auctionsError ? (
          <p role="alert">{auctionsError}</p>
        ) : registeredAuctions.length === 0 ? (
          <div className="mypage-empty">
            등록한 경매가 없습니다.
          </div>
        ) : (
          <ul>
            {registeredAuctions.map((product) => (
              <li key={product.id}>
                <Link to={`/products/${product.id}`}>
                  {product.title}
                </Link>
                <p>현재가: {product.currentPrice.toLocaleString()}원</p>
                <p>상태: {product.status}</p>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="mypage-auctions">
        <h2>내가 낙찰받은 경매</h2>

        {auctionsLoading ? (
          <p>경매 목록을 불러오는 중입니다.</p>
        ) : auctionsError ? (
          <p role="alert">{auctionsError}</p>
        ) : wonAuctions.length === 0 ? (
          <div className="mypage-empty">
            낙찰받은 경매가 없습니다.
          </div>
        ) : (
          <ul>
            {wonAuctions.map((product) => (
              <li key={product.id}>
                <Link to={`/products/${product.id}`}>
                  {product.title}
                </Link>
                <p>낙찰가: {product.currentPrice.toLocaleString()}원</p>
                <p>상태: {product.status}</p>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

export default MyPage;