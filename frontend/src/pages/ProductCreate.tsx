import { useState } from "react";

function ProductCreate() {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [startingPrice, setStartingPrice] = useState("");
  const [bidUnit, setBidUnit] = useState("");
  const [endAt, setEndAt] = useState("");

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    // TODO: 백엔드 상품 등록 API 연결
    console.log("상품 등록", {
      title,
      description,
      startingPrice,
      bidUnit,
      endAt,
    });
  };

  return (
    <main className="page-container">
      <div className="create-container">
        <h1>상품 등록</h1>
        <p>경매에 등록할 상품 정보를 입력하세요.</p>

        <form className="create-form" onSubmit={handleSubmit}>
          <label htmlFor="title">상품명</label>
          <input
            id="title"
            type="text"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            required
          />

          <label htmlFor="description">상품 설명</label>
          <textarea
            id="description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            required
          />

          <label htmlFor="startingPrice">시작 가격</label>
          <input
            id="startingPrice"
            type="number"
            min="0"
            value={startingPrice}
            onChange={(event) => setStartingPrice(event.target.value)}
            required
          />

          <label htmlFor="bidUnit">최소 입찰 단위</label>
          <input
            id="bidUnit"
            type="number"
            min="1"
            value={bidUnit}
            onChange={(event) => setBidUnit(event.target.value)}
            required
          />

          <label htmlFor="endAt">경매 마감 시각</label>
          <input
            id="endAt"
            type="datetime-local"
            value={endAt}
            onChange={(event) => setEndAt(event.target.value)}
            required
          />

          <button type="submit">상품 등록</button>
        </form>
      </div>
    </main>
  );
}

export default ProductCreate;