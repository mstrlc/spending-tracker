import { useEffect, useState } from "react";
import type { HistoryPoint } from "../api/types";
import { fractionDigits } from "../lib/money";
import { formatAsset } from "./holdings";

const PAD = { top: 8, right: 8, bottom: 22, left: 8 };

/** Days since the epoch, so points sit at their real distance in time. */
const day = (iso: string) => Date.parse(`${iso}T00:00:00Z`) / 86_400_000;

/** "31 Mar 2026" for the tooltip. */
const fullDate = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

/** "Mar 26" for an x-axis tick. */
const tick = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { month: "short", year: "2-digit" });

/** A rounded-up axis maximum: 1, 2 or 5 times a power of ten. */
export function niceMax(value: number): number {
  if (value <= 0) return 1;
  const power = 10 ** Math.floor(Math.log10(value));
  return ([1, 2, 5, 10].find((m) => m * power >= value) ?? 10) * power;
}

/**
 * Worth against money put in over time, on one scale from zero. The gap between the lines is the gain.
 * Hover (or tap) anywhere to read the figures of the nearest point.
 */
export function HistoryChart({ points, currency, height = 160 }: { points: HistoryPoint[]; currency: string; height?: number }) {
  const HEIGHT = height;
  // State rather than a ref: the plot mounts only once there are points, and the width must follow it then.
  const [plot, setPlot] = useState<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  const [hover, setHover] = useState<number | null>(null);

  useEffect(() => {
    if (!plot) return;
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(plot);
    return () => observer.disconnect();
  }, [plot]);

  if (points.length < 2) return null;
  const first = day(points[0].date);
  const span = Math.max(1, day(points[points.length - 1].date) - first);
  const max = niceMax(Math.max(...points.flatMap((p) => [p.valueMinor, p.contributedMinor])));
  const plotWidth = Math.max(0, width - PAD.left - PAD.right);
  const x = (p: HistoryPoint) => PAD.left + ((day(p.date) - first) / span) * plotWidth;
  const y = (minor: number) => PAD.top + (1 - Math.max(0, minor) / max) * (HEIGHT - PAD.top - PAD.bottom);
  const path = (pick: (p: HistoryPoint) => number) =>
    points.map((p, i) => `${i === 0 ? "M" : "L"}${x(p).toFixed(1)},${y(pick(p)).toFixed(1)}`).join("");
  // About one tick per 120px, always including the first and last point.
  const tickCount = Math.max(2, Math.min(points.length, Math.floor(plotWidth / 120)));
  const ticks = [...new Set(Array.from({ length: tickCount }, (_, i) => Math.round((i * (points.length - 1)) / (tickCount - 1))))];
  const shown = hover === null ? null : points[hover];

  function track(clientX: number) {
    const left = plot?.getBoundingClientRect().left ?? 0;
    const at = clientX - left;
    let nearest = 0;
    points.forEach((p, i) => {
      if (Math.abs(x(p) - at) < Math.abs(x(points[nearest]) - at)) nearest = i;
    });
    setHover(nearest);
  }

  const last = points[points.length - 1];
  const summary = `Worth ${formatAsset(last.valueMinor, currency)} against ${formatAsset(last.contributedMinor, currency)} ${currency} put in, from ${fullDate(points[0].date)}`;

  return (
    <figure className="history-chart">
      <ul className="category-legend" aria-hidden="true">
        <li>
          <span className="legend-line" />
          Worth
        </li>
        <li>
          <span className="legend-line dashed" />
          Put in
        </li>
      </ul>
      <div
        ref={setPlot}
        className="history-plot"
        style={{ height: HEIGHT }}
        role="img"
        aria-label={summary}
        onPointerMove={(e) => track(e.clientX)}
        onPointerDown={(e) => track(e.clientX)}
        onPointerLeave={() => setHover(null)}
      >
        {width > 0 && (
          <svg width={width} height={HEIGHT} aria-hidden="true">
            <line className="history-grid" x1={PAD.left} x2={width - PAD.right} y1={y(max)} y2={y(max)} />
            <line className="history-baseline" x1={PAD.left} x2={width - PAD.right} y1={y(0)} y2={y(0)} />
            <path className="history-line contributed" d={path((p) => p.contributedMinor)} />
            <path className="history-line worth" d={path((p) => p.valueMinor)} />
            {ticks.map((i) => (
              <text
                key={i}
                className="history-tick"
                x={x(points[i])}
                y={HEIGHT - 6}
                textAnchor={i === 0 ? "start" : i === points.length - 1 ? "end" : "middle"}
              >
                {tick(points[i].date)}
              </text>
            ))}
            <text className="history-tick" x={PAD.left} y={y(max) + 12}>
              {new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(max / 10 ** fractionDigits(currency))}
            </text>
            {shown && (
              <g>
                <line className="history-crosshair" x1={x(shown)} x2={x(shown)} y1={PAD.top} y2={y(0)} />
                <circle className="history-dot contributed" cx={x(shown)} cy={y(shown.contributedMinor)} r={4} />
                <circle className="history-dot worth" cx={x(shown)} cy={y(shown.valueMinor)} r={4} />
              </g>
            )}
          </svg>
        )}
        {shown && (
          <div
            className="history-tooltip"
            style={{ left: Math.min(Math.max(x(shown), 80), width - 80) }}
          >
            <div className="muted small">{fullDate(shown.date)}</div>
            <div>
              Worth <span className="mono">{formatAsset(shown.valueMinor, currency)}</span>
            </div>
            <div>
              Put in <span className="mono">{formatAsset(shown.contributedMinor, currency)}</span>
            </div>
          </div>
        )}
      </div>
    </figure>
  );
}
