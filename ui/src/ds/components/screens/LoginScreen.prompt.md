# LoginScreen

The full-screen sign-in, shown outside the shell. On the left is the hero (flex 1, hidden at ≤800px): a faint accent/purple/ok wash with two breathing radial halos (500px `halo` top-left, 400px `ok` 6% bottom-right) and three floating glass shapes. On the right is the 460px form panel (`surface`, `bdr` left edge).

- **Hero copy:** the headline "Streamline your / cargo integrations" in `display-login` with gradient text `ink` → `accent-ink`; 14.5px `ink3` copy (max 360px); four features with 30px glass icon chips — Visual flow builder with drag-and-drop · Multi-system connectivity · Business rules engine · Real-time monitoring & alerting.
- **Form card** (max 300px): LogoMark 44, "Welcome back" (`title-login`), "Sign in to your workspace", a full-width primary "Sign in with Microsoft", an "or" divider, then a full-width secondary for the alternative method. The prototype's "Back to Dashboard" was a demo link.
- **Provide:** `onSSO`, and `altLabel` + `onAlt` if there is a second method. Override the copy only with product-approved text.

```jsx
<LoginScreen onSSO={signInWithMicrosoft} />
```
