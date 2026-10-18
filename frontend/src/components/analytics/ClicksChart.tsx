import { type KeyboardEvent, useEffect, useRef, useState } from 'react';
import { columnPath, formatDay, labelIndices, niceScale } from '../../lib/chart';
import { formatCount } from '../../lib/format';

interface Props {
  daily: { date: string; clicks: number }[];
}

const HEIGHT = 220;
const MARGIN = { top: 24, right: 4, bottom: 26, left: 32 };
const MAX_BAR = 24;
const GAP = 2;

/** Single-series column chart: one hue, no legend (the heading names it), hover + keyboard readout. */
export function ClicksChart({ daily }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const width = useWidth(containerRef);
  const [active, setActive] = useState<number | null>(null);

  const innerW = Math.max(0, width - MARGIN.left - MARGIN.right);
  const innerH = HEIGHT - MARGIN.top - MARGIN.bottom;
  const peakValue = Math.max(0, ...daily.map((d) => d.clicks));
  const peakIndex = daily.findLastIndex((d) => d.clicks === peakValue);
  const { max, step } = niceScale(peakValue);
  const band = daily.length ? innerW / daily.length : 0;
  const barW = Math.max(1, Math.min(MAX_BAR, band - GAP));
  const y = (v: number) => innerH - (v / max) * innerH;
  const ticks = Array.from({ length: Math.floor(max / step) + 1 }, (_, i) => i * step);
  const labels = new Set(labelIndices(daily.length, width < 480 ? 4 : 7));

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      const delta = e.key === 'ArrowRight' ? 1 : -1;
      setActive((i) => Math.min(daily.length - 1, Math.max(0, (i ?? daily.length - 1) + (i === null ? 0 : delta))));
    } else if (e.key === 'Escape') {
      setActive(null);
    }
  }

  const activeDay = active !== null ? daily[active] : null;
  const tooltipLeft = active !== null ? MARGIN.left + band * active + band / 2 : 0;

  return (
    <div ref={containerRef} className="relative">
      {width > 0 && (
        <svg
          width={width}
          height={HEIGHT}
          role="img"
          tabIndex={0}
          aria-label={`Daily clicks, ${daily.length} days, peak ${peakValue} on ${peakIndex >= 0 ? formatDay(daily[peakIndex].date, true) : 'n/a'}. Use arrow keys to read each day.`}
          onKeyDown={onKeyDown}
          onBlur={() => setActive(null)}
          onPointerLeave={() => setActive(null)}
          className="block overflow-visible rounded-lg focus-visible:outline-2"
        >
          <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
            {/* Recessive hairline grid + y ticks */}
            {ticks.map((t) => (
              <g key={t} transform={`translate(0,${y(t)})`}>
                <line x1={0} x2={innerW} className="stroke-line" strokeWidth={1} shapeRendering="crispEdges" />
                <text x={-8} dy="0.32em" textAnchor="end" className="fill-muted font-mono text-[10px] tabular-nums">
                  {formatCount(t)}
                </text>
              </g>
            ))}

            {daily.map((d, i) => {
              const x = band * i + (band - barW) / 2;
              const h = innerH - y(d.clicks);
              const isActive = active === i;
              return (
                <g key={d.date}>
                  <path
                    d={columnPath(x, y(d.clicks), barW, h)}
                    className="fill-chart transition-opacity"
                    opacity={active === null || isActive ? 1 : 0.45}
                  />
                  {/* Hit target: the whole band, full height (bigger than the mark). */}
                  <rect
                    x={band * i}
                    y={0}
                    width={band}
                    height={innerH}
                    fill="transparent"
                    onPointerEnter={() => setActive(i)}
                  />
                  {labels.has(i) && (
                    <text
                      x={band * i + band / 2}
                      y={innerH + 18}
                      textAnchor={i === 0 && band < 30 ? 'start' : i === daily.length - 1 && band < 30 ? 'end' : 'middle'}
                      className="fill-muted text-[10px]"
                    >
                      {formatDay(d.date)}
                    </text>
                  )}
                </g>
              );
            })}

            {/* Selective direct label: only the peak, on its cap. */}
            {peakValue > 0 && active === null && (
              <text
                x={band * peakIndex + band / 2}
                y={y(peakValue) - 6}
                textAnchor="middle"
                className="fill-ink-soft font-mono text-[11px] font-medium"
              >
                {formatCount(peakValue)}
              </text>
            )}
          </g>
        </svg>
      )}

      {peakValue === 0 && (
        <p className="pointer-events-none absolute inset-x-0 top-[38%] text-center text-sm text-muted">
          No clicks in this period yet
        </p>
      )}

      {activeDay && (
        <div
          role="status"
          className="pointer-events-none absolute top-0 z-10 -translate-x-1/2 whitespace-nowrap rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs shadow-lg"
          style={{ left: Math.min(Math.max(tooltipLeft, 60), width - 60) }}
        >
          <span className="text-muted">{formatDay(activeDay.date, true)}</span>
          <span className="ml-2 font-mono font-medium text-ink">
            {formatCount(activeDay.clicks)} {activeDay.clicks === 1 ? 'click' : 'clicks'}
          </span>
        </div>
      )}
    </div>
  );
}

function useWidth(ref: React.RefObject<HTMLDivElement | null>): number {
  const [width, setWidth] = useState(0);
  useEffect(() => {
    if (!ref.current) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, [ref]);
  return width;
}
