# Mirage API gateway

Users should never have to create a Google Cloud key. This small service holds **your**
Google Maps key on the server, forwards the app's Directions / Geocoding / Places requests
to Google, and meters them as **credits per install**. New installs get free credits;
credit packs are sold in the app through Google Play Billing and verified here.

The app is already built for it: set `MIRAGE_API_BASE` at build time and every request
goes through the gateway with an anonymous install id; the remaining balance comes back
in the `X-Mirage-Credits` header and is shown in Setup.

## What you need (one-time, ~1 hour)

1. **A Google Cloud project** with billing, and one API key with **Application
   restrictions: None** and API restrictions: Directions API, Geocoding API, Places API
   (New). This key lives only on the server.
2. **A second key for the map tiles**, restricted to *Android apps* (package
   `com.mirage.app` + your signing SHA-1) and to the Maps SDK for Android. This one is
   embedded in the app build (`MAPS_API_KEY`) — it is free of charge and useless outside
   your signed app.
3. **Firestore** (Native mode) in the same project.
4. **Deploy** (Cloud Run, source deploy):

   ```bash
   gcloud run deploy mirage-gateway --source server --region us-central1 --allow-unauthenticated \
     --set-env-vars GOOGLE_MAPS_KEY=AIza...,FREE_CREDITS=200,PLAY_PACKAGE=com.mirage.app
   ```

5. **Build the app** with `MIRAGE_API_BASE=https://mirage-gateway-xxxx-uc.a.run.app`
   (a GitHub Actions secret or variable). Users then need no key at all.

## Selling credits

- In Play Console create in-app products `credits_500`, `credits_2500`, `credits_10000`
  with your prices. The mapping to credits is in `index.js` (`PACKS`).
- Give the Cloud Run service account **View financial data** in Play Console → Users and
  permissions, so `/v1/purchase` can verify and acknowledge tokens.
- The app side (Play Billing client + a "Buy credits" button in Setup) is the next step
  and only makes sense once the Play listing and products exist.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/credits` | balance for the install |
| GET | `/v1/directions?…` | same query as Google Directions, no key |
| GET | `/v1/geocode?…` | same query as Google Geocoding, no key |
| POST | `/v1/places/searchText` | same body as Places API (New) Text Search |
| POST | `/v1/purchase` | `{productId, purchaseToken}` → credits |

Every request needs `X-Mirage-Install: <uuid>`; 402 means the install is out of credits.
