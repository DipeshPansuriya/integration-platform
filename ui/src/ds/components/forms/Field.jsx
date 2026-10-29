import * as React from 'react';

/** Label + one control + optional help. Wires id, htmlFor and aria-describedby for you. */
export function Field({ label, help, id, children }) {
  const auto = React.useId();
  const childId = React.isValidElement(children) && children.props.id;
  const fid = id || childId || auto;
  const helpId = help ? fid + '-help' : undefined;
  const control = React.isValidElement(children) ? React.cloneElement(children, { id: fid, 'aria-describedby': helpId }) : children;
  return (
    <div className="ip-field">
      <label className="ip-field-label" htmlFor={fid}>{label}</label>
      {control}
      {help && <div className="ip-field-help" id={helpId}>{help}</div>}
    </div>
  );
}

function FieldRow({ children }) {
  return <div className="ip-field-row">{children}</div>;
}
Field.Row = FieldRow;
