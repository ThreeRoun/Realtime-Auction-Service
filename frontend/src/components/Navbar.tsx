import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

function Navbar() {
  const navigate = useNavigate();

  const [isLoggedIn, setIsLoggedIn] = useState(
    () => !!localStorage.getItem("accessToken")
  );

  useEffect(() => {
    const updateAuthState = () => {
      setIsLoggedIn(!!localStorage.getItem("accessToken"));
    };

    window.addEventListener("auth-change", updateAuthState);

    return () => {
      window.removeEventListener("auth-change", updateAuthState);
    };
  }, []);

  const handleLogout = () => {
    localStorage.removeItem("accessToken");
    setIsLoggedIn(false);
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