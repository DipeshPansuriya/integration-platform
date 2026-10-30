# IB08 — Galaxy GHA Callback Receiver

## What iPaaS sends

When a route has `callback_mode` configured (IMMEDIATE or SCHEDULED), after a
successful delivery the iPaaS callback engine POSTs the target's HTTP response
back to the route's `callback_url`.

### Request format

```
POST {callback_url}
Content-Type: {original response Content-Type, or application/octet-stream}
X-Delivery-Id: {uuid}
X-Original-Status: {HTTP status the target returned, e.g. 200}

{original response body bytes}
```

### Expected response

| Status  | Meaning                                    |
|---------|--------------------------------------------|
| 2xx     | Callback accepted — iPaaS marks it SENT    |
| 4xx/5xx | Rejected — iPaaS retries up to 5 attempts  |

### Retry behaviour

- Max 5 attempts per callback
- Failed attempts are retried on the next CALLBACK_SEND job cycle (every 15 s for SCHEDULED)
- After 5 failures the row moves to DEAD state — no further retries

## What Galaxy GHA needs to implement

Galaxy should expose a new endpoint:

```
POST /api/integration/callback
```

This endpoint:
1. Validates the `X-Delivery-Id` header (UUID format)
2. Reads the `X-Original-Status` header
3. Stores or processes the response body as needed (e.g. update shipment status)
4. Returns `200 OK` on success

### Authentication

The callback URL stored on the iPaaS route can include Galaxy's authentication
token as a query parameter or the route can use a target endpoint with API_KEY
or BEARER_TOKEN auth. The iPaaS callback dispatcher does not add its own auth
headers beyond `X-Delivery-Id` and `X-Original-Status`.

### Notes

- Galaxy is under TFVC — changes are made separately, not from this repo
- The callback URL is configured per-route in the iPaaS admin UI
- No Galaxy code changes are included in this PR
