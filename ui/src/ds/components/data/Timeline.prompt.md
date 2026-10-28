# Timeline

The vertical event log on the Activity page and in detail drawers: a 2px `bdr` rail, and 12px dots with a 2px `surface` ring. Each entry has the time (11px `ink3`, tabular) above 13px `ink2` text, with entity names in `<strong>` 600 `ink`.

- **Dot tones:** `ok` success, `err` failure, `warn` threshold breach, `info` a user or system action.
- **Compact** (`compact`): 22px indent and 12px between items, used for "Recent Events" in the System drawer.
- **Order:** newest first. Times are 24-hour HH:MM:SS.
- **Provide:** `items` `{ time, tone, text }` where `text` may contain `<strong>` entity names. The text must say what happened; the dot colour only reinforces it.

```jsx
<Timeline items={[
  { time: '10:42:18', tone: 'ok', text: <><strong>IMP-SLOT-BOOKING</strong> processed message msg-8a4f2c successfully</> },
  { time: '10:30:12', tone: 'err', text: <><strong>CUSTOMS-RESPONSE</strong> failed: connection timeout to customs-api:443</> }]} />
```
