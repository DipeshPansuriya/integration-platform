import * as React from 'react';

/** Static preview of a mapping: source and target nodes joined through a transform node by dashed connectors. */
export function MappingCanvas({ source = 'FWB (XML)', target = 'CUSTOMS (JSON)', transform = 'Transform', height = 200 }) {
  const box = React.useRef(null);
  const a = React.useRef(null);
  const b = React.useRef(null);
  const t = React.useRef(null);
  const [geo, setGeo] = React.useState(null);
  React.useLayoutEffect(() => {
    const calc = () => {
      if (!box.current || !a.current || !b.current || !t.current) return;
      if (typeof box.current.getBoundingClientRect !== "function") return;
      const B = box.current.getBoundingClientRect();
      const A = a.current.getBoundingClientRect();
      const C = b.current.getBoundingClientRect();
      const T = t.current.getBoundingClientRect();
      const s = B.width ? box.current.offsetWidth / B.width : 1;
      const rel = (x, y) => [(x - B.left) * s, (y - B.top) * s];
      setGeo({
        w: box.current.offsetWidth, h: box.current.offsetHeight,
        l1: [...rel(A.left + A.width * 0.75, A.bottom), ...rel(T.left + 12, T.top)],
        l2: [...rel(T.right - 12, T.top), ...rel(C.left + C.width * 0.25, C.bottom)],
      });
    };
    calc();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(calc);
    ro.observe(box.current);
    return () => ro.disconnect();
  }, [source, target, transform]);
  return (
    <div className="ip-canvas" ref={box} style={{ minHeight: height }} role="img" aria-label={source + ' to ' + transform + ' to ' + target}>
      {geo && (
        <svg viewBox={'0 0 ' + geo.w + ' ' + geo.h} aria-hidden="true">
          <path d={'M' + geo.l1[0] + ' ' + geo.l1[1] + ' L' + geo.l1[2] + ' ' + geo.l1[3]} />
          <path d={'M' + geo.l2[0] + ' ' + geo.l2[1] + ' L' + geo.l2[2] + ' ' + geo.l2[3]} />
        </svg>
      )}
      <div ref={a} className="ip-canvas-node ip-tone-accent" style={{ top: 10, left: 20 }}><span className="ip-canvas-dot" />{source}</div>
      <div ref={b} className="ip-canvas-node ip-tone-ok" style={{ top: 10, right: 20 }}><span className="ip-canvas-dot" />{target}</div>
      <div ref={t} className="ip-canvas-node ip-tone-warn" style={{ top: 100, left: '50%', transform: 'translateX(-50%)' }}><span className="ip-canvas-dot" />{transform}</div>
    </div>
  );
}
