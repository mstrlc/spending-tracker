import { useEffect } from "react";
import { useSearchParams } from "react-router";
import { ChevronLeft, ChevronRight } from "./icons";
import { currentMonth, formatMonth, isMonth, monthRange, shiftMonth, type Month } from "./lib/dates";

/** The month a list page shows, from ?month= (absent means the current month). */
export function useMonthParam() {
  const [params, setParams] = useSearchParams();
  const monthParam = params.get("month");
  const month = isMonth(monthParam) ? monthParam : currentMonth();
  const goTo = (m: Month) => setParams(m === currentMonth() ? {} : { month: m });
  return { month, ...monthRange(month), goTo };
}

/** Month name with previous / next buttons, plus a way back when viewing another month. */
export function MonthTitle({ month, goTo }: { month: Month; goTo: (m: Month) => void }) {
  return (
    <span className="month-title">
      {/* Every month of the year sits in the same grid cell, all but this one hidden, so the title is as wide
          as the longest name and the arrows stay put while paging through months. */}
      <span className="month-name">
        {Array.from({ length: 12 }, (_, i) => {
          const m = `${month.slice(0, 4)}-${String(i + 1).padStart(2, "0")}`;
          return (
            <span key={m} aria-hidden={m !== month} className={m === month ? undefined : "month-name-sizer"}>
              {formatMonth(m)}
            </span>
          );
        })}
      </span>
      <span className="month-nav">
        <button
          className="icon-button"
          aria-label="Previous month"
          title="Previous month (←)"
          onClick={() => goTo(shiftMonth(month, -1))}
        >
          <ChevronLeft />
        </button>
        <button
          className="icon-button"
          aria-label="Next month"
          title="Next month (→)"
          onClick={() => goTo(shiftMonth(month, 1))}
        >
          <ChevronRight />
        </button>
      </span>
      {month !== currentMonth() && (
        <button className="ghost this-month" onClick={() => goTo(currentMonth())}>
          This month
        </button>
      )}
    </span>
  );
}

/** N calls onAdd, ← / → change month. Ignored while typing, with modifiers, or with a sheet open. */
export function useMonthShortcuts(month: Month, goTo: (m: Month) => void, onAdd: () => void) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented) return;
      const target = e.target as HTMLElement;
      if (target.closest("input, select, textarea, .sheet") || document.querySelector(".sheet")) return;
      if (e.key === "n" || e.key === "N") {
        e.preventDefault();
        onAdd();
      } else if (e.key === "ArrowLeft") {
        goTo(shiftMonth(month, -1));
      } else if (e.key === "ArrowRight") {
        goTo(shiftMonth(month, 1));
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });
}
