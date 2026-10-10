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
  createdAt: string;
}

export async function getUserProfile(
  userId: string
): Promise<UserProfile> {
  const response = await fetch(
    `/api/users/${encodeURIComponent(userId)}`
  );

  if (!response.ok) {
    throw new Error("사용자 정보를 불러오지 못했습니다.");
  }

  return response.json();
}