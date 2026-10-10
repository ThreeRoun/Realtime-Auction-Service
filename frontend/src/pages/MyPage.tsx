import { useEffect, useState } from "react";
import { jwtDecode } from "jwt-decode";
import { getUserProfile, type UserProfile } from "../api/auth";

interface JwtPayload {
  sub: string;
}

function MyPage() {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

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
      } catch (err) {
        setError(
          err instanceof Error
            ? err.message
            : "사용자 정보를 불러오지 못했습니다."
        );
      } finally {
        setLoading(false);
      }
    };

    void loadProfile();
  }, []);

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
          </div>
        )}
      </section>

      <section className="mypage-auctions">
        <h2>참여 중인 경매</h2>

        <div className="mypage-empty">
          현재 참여 중인 경매가 없습니다.
        </div>
      </section>
    </main>
  );
}

export default MyPage;