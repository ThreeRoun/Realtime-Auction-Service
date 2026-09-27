import { Link } from "react-router-dom";

function AuctionResult() {
  return (
    <main className="page-container">
      <section className="result-container">
        <h1>경매 결과</h1>

        <div className="result-card">
          <div className="result-image">상품 이미지</div>

          <div className="result-info">
            <h2>무선 헤드폰</h2>

            <p className="result-status">경매가 종료되었습니다.</p>

            <p>
              <strong>최종 낙찰가:</strong> 75,000원
            </p>

            <p>
              <strong>낙찰자:</strong> 경매왕
            </p>

            <p>
              <strong>경매 상태:</strong> 낙찰 확정
            </p>
          </div>
        </div>

        <Link to="/" className="detail-button">
          상품 목록으로 돌아가기
        </Link>
      </section>
    </main>
  );
}

export default AuctionResult;