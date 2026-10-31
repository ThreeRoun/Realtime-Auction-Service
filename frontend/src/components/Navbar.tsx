import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

function hasValidToken() {
  const token = localStorage.getItem("accessToken");
  const expiresAt = localStorage.getItem("expiresAt");

  if (!token || !expiresAt) {
    return false;
  }

  const expirationTime = Date.parse(expiresAt);

  if (!Number.isFinite(expirationTime) || Date.now() >= expirationTime) {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("expiresAt");
    return false;
  }

  return true;
}

function Navbar() {
  const navigate = useNavigate();

  const [isLoggedIn, setIsLoggedIn] = useState(hasValidToken);

  useEffect(() => {
    const updateAuthState = () => {
      setIsLoggedIn(hasValidToken());
    };

    window.addEventListener("auth-change", updateAuthState);

    return () => {
      window.removeEventListener("auth-change", updateAuthState);
    };
  }, []);

  useEffect(() => {
    if (!isLoggedIn) {
      return;
    }

    const expiresAt = localStorage.getItem("expiresAt");
    const expirationTime = Date.parse(expiresAt ?? "");
    const remainingTime = expirationTime - Date.now();

    if (!Number.isFinite(remainingTime) || remainingTime <= 0) {
      localStorage.removeItem("accessToken");
      localStorage.removeItem("expiresAt");
      window.dispatchEvent(new Event("auth-change"));
      return;
    }

    const timer = window.setTimeout(() => {
      localStorage.removeItem("accessToken");
      localStorage.removeItem("expiresAt");
      window.dispatchEvent(new Event("auth-change"));
    }, Math.min(remainingTime, 2_147_483_647));

    return () => window.clearTimeout(timer);
  }, [isLoggedIn]);

  const handleLogout = () => {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("expiresAt");
    setIsLoggedIn(false);
    window.dispatchEvent(new Event("auth-change"));
    navigate("/login");
  };

  return (
    <nav className="navbar">
      <Link to="/" className="navbar-logo">
        실시간 경매
      </Link>

      <div className="navbar-menu">
        <Link to="/">상품 목록</Link>
        <Link to="/products/new">상품 등록</Link>
        <Link to="/mypage">마이페이지</Link>

        {isLoggedIn ? (
          <button type="button" onClick={handleLogout}>
            로그아웃
          </button>
        ) : (
          <>
            <Link to="/login">로그인</Link>
            <Link to="/signup">회원가입</Link>
          </>
        )}
      </div>
    </nav>
  );
}

export default Navbar;