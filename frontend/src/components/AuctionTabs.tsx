export type AuctionTab = "active" | "ended";

interface AuctionTabsProps {
  activeTab: AuctionTab;
  onTabChange: (tab: AuctionTab) => void;
  activeCount?: number;
  endedCount?: number;
}

function AuctionTabs({
  activeTab,
  onTabChange,
  activeCount,
  endedCount,
}: AuctionTabsProps) {
  return (
    <div className="auction-tabs">
      <button
        type="button"
        className={`auction-tab ${activeTab === "active" ? "active" : ""}`}
        aria-pressed={activeTab === "active"}
        onClick={() => onTabChange("active")}
      >
        진행 중{activeCount !== undefined ? ` (${activeCount})` : ""}
      </button>

      <button
        type="button"
        className={`auction-tab ${activeTab === "ended" ? "active" : ""}`}
        aria-pressed={activeTab === "ended"}
        onClick={() => onTabChange("ended")}
      >
        종료{endedCount !== undefined ? ` (${endedCount})` : ""}
      </button>
    </div>
  );
}

export default AuctionTabs;