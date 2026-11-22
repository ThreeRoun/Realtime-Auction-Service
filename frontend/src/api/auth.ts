export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  expiresAt: string;
}

export async function login(
  request: LoginRequest
): Promise<LoginResponse> {
  const response = await fetch("/api/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error("로그인에 실패했습니다.");
  }

  return response.json();
}

export interface SignupRequest {
  username: string;
  email: string;
  password: string;
  initialCredit?: number;
}

export interface SignupResponse {
  id: string;
  username: string;
  email: string;
  credit: number;
  createdAt: string;
}

export async function signup(
  request: SignupRequest
): Promise<SignupResponse> {
  const response = await fetch("/api/users", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    if (response.status === 409) {
      throw new Error("이미 사용 중인 사용자 이름 또는 이메일입니다.");
    }

    if (response.status === 400) {
      throw new Error("회원가입 정보를 확인해 주세요.");
    }

    throw new Error("회원가입에 실패했습니다.");
  }

  return response.json();
}

export interface UserProfile {
  id: string;
  username: string;
  email: string;
  credit: number;
  availableCredit?: number;
  createdAt: string;
}

export async function getUserProfile(
  userId: string
): Promise<UserProfile> {
  const accessToken = localStorage.getItem("accessToken");

  if (!accessToken) {
    throw new Error("로그인이 필요합니다.");
  }

  // 사용자 조회 API는 로그인한 사용자만 호출할 수 있다 (엔진 이슈 #149). 본인 id를 조회하므로 이메일과 크레딧까지
  // 전체 정보가 온다. 다른 사용자를 조회하면 id와 닉네임만 오므로, 이 함수는 본인 조회용이다.
  const response = await fetch(
    `/api/users/${encodeURIComponent(userId)}`,
    { headers: { Authorization: `Bearer ${accessToken}` } }
  );

  if (response.status === 401) {
    // 입찰 API(bids.ts)와 같게, 토큰이 유효하지 않으면 로그인 정보를 지우고 다시 로그인하게 한다
    localStorage.removeItem("accessToken");
    localStorage.removeItem("expiresAt");
    window.dispatchEvent(new Event("auth-change"));
    throw new Error("로그인이 만료되었습니다. 다시 로그인해 주세요.");
  }

  if (!response.ok) {
    throw new Error("사용자 정보를 불러오지 못했습니다.");
  }

  return response.json();
}