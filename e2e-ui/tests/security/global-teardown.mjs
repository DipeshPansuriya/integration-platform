import { teardownAll } from '../screens/boot-state.mjs';
import { takeControl } from './control-state.mjs';

export default async function globalTeardown() {
  const control = takeControl();
  if (control) {
    clearInterval(control.refresh);
    await new Promise((resolve) => control.server.close(resolve));
  }
  await teardownAll();
}
