/**
 * Row 278 T57: the designer keeps the PREVIOUS expression when the emitter refuses the drawn links
 * (one target drawn twice), so saving then would store a stale expression beside the new
 * fieldMappings. The save is refused with the emitter's own refusal while it stands.
 */

import { emitJsonata } from './emit/emit';
import type { MappingGraph } from './graph';

export function saveRefusal(graph: MappingGraph): string | null {
  return emitJsonata(graph.links).refusal;
}
