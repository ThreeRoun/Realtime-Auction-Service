import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { signup } from "../api/auth";

function Signup() {
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [initialCredit, setInitialCredit] = useState("500000");
  const [errorMessage, setErrorMessage] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const navigate = useNavigate();

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setErrorMessage("");

    if (password !== confirmPassword) {
      setErrorMessage("비밀번호가 일치하지 않습니다.");
      return;
    }

    if (password.length < 4) {
      setErrorMessage("비밀번호는 4자 이상이어야 합니다.");
      return;
    }

    const credit = Number(initialCredit);

    if (!Number.isSafeInteger(credit) || credit < 0) {
      setErrorMessage("초기 크레딧은 0 이상의 정수여야 합니다.");
      return;
    }

    setIsSubmitting(true);

    try {
      await signup({
        username: username.trim(),
        email: email.trim(),
        password,
        initialCredit: credit,
      });

      navigate("/login");
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : "회원가입에 실패했습니다."
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <main className="page-container">
      <div className="login-container">
        <h1>회원가입</h1>
        <p>실시간 경매 서비스에 가입하세요.</p>

        <form className="login-form" onSubmit={handleSubmit}>
          <label htmlFor="signup-username">사용자 이름</label>
          <input
            id="signup-username"
            type="text"
            maxLength={50}
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            required
          />

          <label htmlFor="signup-email">이메일</label>
          <input
            id="signup-email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />

          <label htmlFor="signup-password">비밀번호</label>
          <input
            id="signup-password"
            type="password"
            minLength={4}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />

          <label htmlFor="signup-confirm-password">비밀번호 확인</label>
          <input
            id="signup-confirm-password"
            type="password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            required
          />

          <label htmlFor="signup-credit">초기 크레딧 (선택)</label>
          <input
            id="signup-credit"
            type="number"
            min={0}
            step={1}
            value={initialCredit}
            onChange={(event) => setInitialCredit(event.target.value)}
          />

          {errorMessage && <p role="alert">{errorMessage}</p>}

          <button type="submit" disabled={isSubmitting}>
            {isSubmitting ? "가입 중..." : "회원가입"}
          </button>
        </form>

        <p>
          이미 계정이 있나요? <Link to="/login">로그인</Link>
        </p>
      </div>
    </main>
  );
}

export default Signup;