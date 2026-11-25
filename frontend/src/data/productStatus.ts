// 엔진 ProductStatus 값을 화면에 보여줄 한글 이름으로 바꾼다.
const STATUS_LABELS: Record<string, string> = {
  PENDING: "등록 대기",
  IN_PROGRESS: "진행 중",
  EXTENDED: "마감 연장",
  SOLD: "낙찰",
  UNSOLD: "유찰",
  COMPLETED: "완료",
  CANCELLED: "경매 취소",
};

// 입찰할 수 있는 진행중 상태 (엔진 BidService가 입찰을 허용하는 상태와 같다)
export const LIVE_STATUSES = ["IN_PROGRESS", "EXTENDED"];

export function getStatusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}
