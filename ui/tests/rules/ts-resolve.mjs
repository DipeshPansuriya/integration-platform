// E6 lane D (T-026, T-028): lets `node --test` load the console's TypeScript modules directly.
// Node 24 strips the types itself (no test library is added); what it does not do is resolve the
// extensionless relative imports that Vite and tsc use (`./zen`, not `./zen.ts`). This hook retries
// such a specifier with `.ts` - nothing else changes. Loaded with `node --import ./tests/rules/ts-resolve.mjs`.

import { registerHooks } from 'node:module';

const RELATIVE = /^\.\.?\//;
const HAS_EXTENSION = /\.[cm]?[jt]sx?$|\.json$/;

registerHooks({
  resolve(specifier, context, nextResolve) {
    try {
      return nextResolve(specifier, context);
    } catch (error) {
      if (error?.code === 'ERR_MODULE_NOT_FOUND' && RELATIVE.test(specifier) && !HAS_EXTENSION.test(specifier)) {
        return nextResolve(`${specifier}.ts`, context);
      }
      throw error;
    }
  }
});
