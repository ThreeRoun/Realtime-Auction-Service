import { Link } from "react-router-dom";

function Navbar() {
  return (
    <nav className="navbar">
      <Link to="/" className="navbar-logo">
        실시간 경매
      </Link>

      <div className="navbar-menu">
        <Link to="/">상품 목록</Link>
        <Link to="/products/new">상품 등록</Link>
        <Link to="/mypage">마이페이지</Link>
        <Link to="/login">로그인</Link>
      </div>
    </nav>
  );
}

export default Navbar;