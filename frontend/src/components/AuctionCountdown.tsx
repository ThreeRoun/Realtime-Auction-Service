import { useEffect, useState } from "react";

interface AuctionCountdownProps {
  endAt: string;
}

function getRemainingTime(endAt: string) {
  const remaining = new Date(endAt).getTime() - Date.now();

  if (!Number.isFinite(remaining)) {
    return "마감 시간 정보 없음";
  }

  if (remaining <= 0) {
    return "경매 마감";
  }

  const totalSeconds = Math.ceil(remaining / 1000);
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  return `${days}일 ${hours}시간 ${minutes}분 ${seconds}초`;
}

function AuctionCountdown({ endAt }: AuctionCountdownProps) {
  const [remainingTime, setRemainingTime] = useState(() =>
    getRemainingTime(endAt)
  );

  useEffect(() => {
    const updateRemainingTime = () => {
      setRemainingTime(getRemainingTime(endAt));
    };

    updateRemainingTime();

    const intervalId = window.setInterval(updateRemainingTime, 1000);

    return () => {
      window.clearInterval(intervalId);
    };
  }, [endAt]);

  return <div className="product-end-time">남은 시간: {remainingTime}</div>;
}

export default AuctionCountdown;