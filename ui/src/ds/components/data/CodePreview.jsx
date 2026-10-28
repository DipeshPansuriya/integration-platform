import * as React from 'react';

function words(text, out, key) {
  text.split(/(\d+(?:\.\d+)?|[A-Za-z][A-Za-z ]*[A-Za-z]|[A-Za-z])/).forEach((p) => {
    if (!p) return;
    if (/^\d+(?:\.\d+)?$/.test(p)) out.push(<span key={key.n++} className="n">{p}</span>);
    else if (/^[A-Za-z]/.test(p)) out.push(<span key={key.n++} className="s">{p}</span>);
    else out.push(p);
  });
}

function highlightXml(code) {
  const out = [];
  const key = { n: 0 };
  let last = 0;
  const re = /<[^>]*>/g;
  let m;
  while ((m = re.exec(code))) {
    if (m.index > last) words(code.slice(last, m.index), out, key);
    const t = /^<(\/?)([^\s>/]+)([\s\S]*?)(\/?)>$/.exec(m[0]);
    if (t) {
      out.push('<' + t[1]);
      out.push(<span key={key.n++} className="k">{t[2]}</span>);
      let rest = t[3];
      const ar = /(\s+)([^\s=]+)(=)("[^"]*"|'[^']*')/g;
      let a;
      let pos = 0;
      while ((a = ar.exec(rest))) {
        if (a.index > pos) out.push(rest.slice(pos, a.index));
        out.push(a[1]);
        out.push(<span key={key.n++} className="k">{a[2]}</span>);
        out.push('=');
        out.push(<span key={key.n++} className="s">{a[4]}</span>);
        pos = ar.lastIndex;
      }
      if (pos < rest.length) out.push(rest.slice(pos));
      out.push(t[4] + '>');
    } else out.push(m[0]);
    last = re.lastIndex;
  }
  if (last < code.length) words(code.slice(last), out, key);
  return out;
}

function highlightJson(code) {
  const out = [];
  let n = 0;
  let last = 0;
  const re = /("(?:[^"\\]|\\.)*")(\s*:)?|(-?\d+(?:\.\d+)?)|\b(true|false|null)\b/g;
  let m;
  while ((m = re.exec(code))) {
    if (m.index > last) out.push(code.slice(last, m.index));
    if (m[1]) {
      out.push(<span key={n++} className={m[2] ? 'k' : 's'}>{m[1]}</span>);
      if (m[2]) out.push(m[2]);
    } else out.push(<span key={n++} className="n">{m[3] || m[4]}</span>);
    last = re.lastIndex;
  }
  if (last < code.length) out.push(code.slice(last));
  return out;
}

/** Scrollable schema / payload sample with key, string and number colouring. language: 'xml' | 'json' | 'plain'. */
export function CodePreview({ code, language = 'xml', maxHeight = 180, label = 'Schema preview' }) {
  const body = language === 'xml' ? highlightXml(code) : language === 'json' ? highlightJson(code) : code;
  return <pre className="ip-code" style={{ maxHeight }} tabIndex={0} aria-label={label}>{body}</pre>;
}
