# EntityCard

The card for systems, rules and mappings in the `ip-grid-cards` grid (auto-fill, minimum 260px). The header has a 36px icon chip in the tone's tint, the name in `title-card` and a type line (11px 500 `ink3`) joining facts with a middle dot. KeyValue rows follow.

- **Systems:** the icon is tinted by health (`accent` or `ok` healthy, `warn` slow, `err` down). Type line "Protocol · Auth"; rows Status, Latency, Last check.
- **Rules:** "12 conditions · 5 outputs"; rows Hit rate, Last edit.
- **Mappings:** the name is "SOURCE → TARGET" with a real arrow. Type line "XML to JSON · 24 fields"; rows Used by, Updated.
- **Provide:** `icon`, `tone`, `name`, `typeLine`, `rows`, and `onOpen` to open the detail drawer.

```jsx
<EntityCard icon="systems" tone="accent" name="ACS Gateway" typeLine="REST API · HMAC Auth"
  rows={[{ label: 'Status', value: 'Healthy', tone: 'ok' }, { label: 'Latency', value: '12ms' }]}
  onOpen={() => openSystem('acs')} />
```
