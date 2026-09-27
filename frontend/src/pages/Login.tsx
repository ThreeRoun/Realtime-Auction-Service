import { useState } from "react";

function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    // TODO: 백엔드 로그인 API 연결
    console.log("로그인 시도", { email, password });
  };

  return (
    <main className="page-container">
      <div className="login-container">
        <h1>로그인</h1>
        <p>실시간 경매 서비스에 로그인하세요.</p>

        <form className="login-form" onSubmit={handleSubmit}>
          <label htmlFor="email">이메일</label>
          <input
            id="email"
            type="email"
            placeholder="example@email.com"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />

          <label htmlFor="password">비밀번호</label>
          <input
            id="password"
            type="password"
            placeholder="비밀번호를 입력하세요"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />

          <button type="submit">로그인</button>
        </form>
      </div>
    </main>
  );
}

export default Login;