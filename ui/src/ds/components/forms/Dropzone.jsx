import * as React from 'react';
import { cx } from '../_shared/util.js';
import { Icon } from '../brand/Icon.jsx';

/** Dashed drop target for schema files; also click or keyboard to browse. */
export function Dropzone({ title = 'Drop your schema file here', hint = 'XSD, JSON Schema, WSDL, or EDIFACT definition', accept, multiple = false, onFiles }) {
  const [over, setOver] = React.useState(false);
  const pick = (files) => { if (onFiles && files && files.length) onFiles(Array.from(files)); };
  return (
    <label
      className={cx('ip-drop', over && 'is-over')}
      onDragOver={(e) => { e.preventDefault(); setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); pick(e.dataTransfer.files); }}
    >
      <input type="file" accept={accept} multiple={multiple} onChange={(e) => pick(e.target.files)} />
      <Icon name="upload" size={28} />
      <span className="ip-drop-title">{title}</span>
      <span className="ip-drop-hint">{hint}</span>
    </label>
  );
}
