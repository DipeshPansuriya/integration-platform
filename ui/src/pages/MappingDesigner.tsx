/**
 * E7 seam (arjun-fullstack, F5): route + menu stub for E7 lane D1's mapping canvas (`#/mappings/<routeId>`, T-034 /
 * T-035). The lane fills ONLY this page file and never edits App.tsx. Empty on purpose: the placeholder names the
 * route it was opened for so the Playwright perf/navigation pages stay resolvable until the canvas lands.
 */
export function MappingDesigner({ routeId }: { routeId: string | null }) {
  return (
    <main data-page="mapping-designer">
      <h2>Mapping designer</h2>
      <p>{routeId ? `Route ${routeId}` : 'Open a mapping from its route.'}</p>
    </main>
  );
}