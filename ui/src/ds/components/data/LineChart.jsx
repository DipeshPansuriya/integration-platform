import * as React from 'react';

/** Throughput line: area fill, faint grid, emphasised end point. Measures its own width so labels never stretch. */
export function LineChart({ data, max, xLabels, height = 130, yTicks = 4, label = 'Throughput' }) {
  const box = React.useRef(null);
  const [w, setW] = React.useState(560);
  React.useLayoutEffect(() => {
    const el = box.current;
    if (!el) return undefined;
    const measure = () => setW(Math.max(240, Math.round(el.clientWidth)));
    measure();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const gid = 'ipg' + React.useId().replace(/[^a-zA-Z0-9]/g, '');
  const n = data.length;
  const top = Math.max(...data);
  const mx = max || Math.ceil(top / 10) * 10 || 100;
  const labels = xLabels || [0, 4, 8, 12, 16, 20].filter((h) => h < n).map((h) => ({ at: h, text: String(h).padStart(2, '0') }));
  const px = 36, pt = 8, pb = 20;
  const pw = w - px * 2, ph = height - pt - pb;
  const X = (i) => px + (n > 1 ? (pw * i) / (n - 1) : 0);
  const Y = (v) => pt + ph * (1 - v / mx);
  let line = '';
  data.forEach((v, i) => { line += (i ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(v).toFixed(1); });
  const area = 'M' + px + ',' + (pt + ph) + line.replace(/^M/, 'L') + 'L' + (px + pw) + ',' + (pt + ph) + 'Z';
  const grid = [];
  for (let i = 0; i < yTicks; i++) {
    const y = pt + (ph * i) / (yTicks - 1);
    grid.push(
      <g key={i}>
        <line className="ip-chart-grid" x1={px} y1={y} x2={w - px} y2={y} />
        <text className="ip-chart-label" x={px - 6} y={y + 3} textAnchor="end">{Math.round(mx - (mx * i) / (yTicks - 1))}</text>
      </g>
    );
  }
  return (
    <div className="ip-chart">
      <div ref={box} className="ip-chart-box">
        <svg width={w} height={height} viewBox={'0 0 ' + w + ' ' + height} role="img" aria-label={label + ': ' + n + ' points, latest ' + data[n - 1]}>
          <defs>
            <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
              <stop className="ip-chart-stop" offset="0%" stopOpacity="0.25" />
              <stop className="ip-chart-stop" offset="100%" stopOpacity="0" />
            </linearGradient>
          </defs>
          {grid}
          {labels.map((l) => (
            <text key={l.at} className="ip-chart-label" x={X(l.at)} y={height - 4} textAnchor="middle">{l.text}</text>
          ))}
          <path d={area} fill={'url(#' + gid + ')'} />
          <path className="ip-chart-line" d={line} />
          <circle className="ip-chart-end" cx={X(n - 1)} cy={Y(data[n - 1])} r="4" />
        </svg>
      </div>
    </div>
  );
}
