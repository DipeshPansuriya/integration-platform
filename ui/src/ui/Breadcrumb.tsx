// Row 572 S0 (spec 4.7): `Section > Item` as <nav aria-label="Breadcrumb"><ol>; the last item is the current page
// (aria-current="page", plain text). Hook-free. Labels are React text children (escaped).
export type Crumb = { label: string; href?: string };

export function Breadcrumb({ items }: { items: Crumb[] }) {
  if (items.length === 0) return null;
  const last = items.length - 1;
  return (
    <nav aria-label="Breadcrumb" className="breadcrumb">
      <ol>
        {items.map((item, index) =>
          index === last || !item.href ? (
            <li key={index} aria-current={index === last ? 'page' : undefined}>{item.label}</li>
          ) : (
            <li key={index}>
              <a href={item.href}>{item.label}</a>
            </li>
          )
        )}
      </ol>
    </nav>
  );
}
