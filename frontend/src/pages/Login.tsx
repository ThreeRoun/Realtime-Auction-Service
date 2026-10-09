import { useState } from "react";
import { login } from "../api/auth";
import { useNavigate } from "react-router-dom";

function Login() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const navigate = useNavigate();

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    try {
      const result = await login({ username, password });
      localStorage.setItem("accessToken", result.accessToken);
      console.log("로그인 성공");
      navigate("/");
    } catch (error) {
      console.error("로그인 실패", error);
    }
  };

  return (
    <main className="page-container">
      <div className="login-container">
        <h1>로그인</h1>
        <p>실시간 경매 서비스에 로그인하세요.</p>

        <form className="login-form" onSubmit={handleSubmit}>
          <label htmlFor="username">사용자 이름</label>
          <input
            id="username"
            type="text"
            placeholder="사용자 이름을 입력하세요"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
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