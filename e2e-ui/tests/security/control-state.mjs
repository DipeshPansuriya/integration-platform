// Runner-process state shared by the security global setup and teardown.
let control = null;

export function setControl(value) {
  control = value;
}

export function takeControl() {
  const value = control;
  control = null;
  return value;
}
