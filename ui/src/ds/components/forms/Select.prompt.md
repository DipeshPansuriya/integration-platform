# Select

A native select styled like Input, with a 12px chevron in `ink3` drawn with `currentColor`. The prototype's data-URI chevron was fixed to one grey and ignored dark mode.

- **Provide:** `options` as strings or `{ value, label }`, plus the usual select props.
- Use a leading "Select a contract..." style option when no default makes sense, and keep option labels in the product's own words ("Every 30 seconds", "Certificate (mTLS)").

```jsx
<Select options={['REST API', 'SOAP/XML', 'EDIFACT', 'Type-B Telex', 'SFTP']} value={protocol} onChange={(e) => setProtocol(e.target.value)} />
```
