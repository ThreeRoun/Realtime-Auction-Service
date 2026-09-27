function MyPage() {
  return (
    <main className="page-container">
      <section className="page-header">
        <h1>마이페이지</h1>
        <p>내 정보와 경매 참여 현황을 확인할 수 있습니다.</p>
      </section>

      <section className="mypage-profile">
        <h2>내 정보</h2>

        <div className="profile-info">
          <p>
            <strong>닉네임:</strong> 경매왕
          </p>
          <p>
            <strong>이메일:</strong> user@example.com
          </p>
          <p>
            <strong>보유 크레딧:</strong> 500,000원
          </p>
        </div>
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