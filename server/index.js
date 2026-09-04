// Mirage API gateway
//
// The app never holds a billable Google key. It calls this service with an anonymous
// install id; the service forwards the request to Google with ITS key, charges the
// install one credit per call, and echoes the remaining balance in X-Mirage-Credits.
// Credits are granted for free on first contact and topped up by verified Play purchases.
//
// Deploy (Cloud Run):
//   gcloud run deploy mirage-gateway --source server --region us-central1 \
//     --set-env-vars GOOGLE_MAPS_KEY=<server-side key>,FREE_CREDITS=200,PLAY_PACKAGE=com.mirage.app
//   then build the app with MIRAGE_API_BASE=https://mirage-gateway-....run.app
//
// Firestore: collection "installs" { credits: number, created: ts, lastSeen: ts }
//            collection "purchases" { token: docId, installId, productId, credits, ts }

import express from "express";
import { Firestore, FieldValue } from "@google-cloud/firestore";
import { google } from "googleapis";

const KEY = process.env.GOOGLE_MAPS_KEY;
const FREE_CREDITS = Number(process.env.FREE_CREDITS ?? 200);
const PLAY_PACKAGE = process.env.PLAY_PACKAGE ?? "com.mirage.app";
// Credit packs sold in the app (Play product id -> credits). Prices are set in Play Console.
const PACKS = { credits_500: 500, credits_2500: 2500, credits_10000: 10000 };
const COST = { directions: 1, geocode: 1, places: 1 };

if (!KEY) { console.error("GOOGLE_MAPS_KEY is required"); process.exit(1); }

const db = new Firestore();
const app = express();
app.use(express.json({ limit: "64kb" }));

/** Identify the install and make sure it has an account (with its free credits). */
async function account(req, res, next) {
  const id = String(req.get("X-Mirage-Install") ?? "").trim();
  if (!/^[0-9a-f-]{36}$/i.test(id)) return res.status(401).json({ error: "missing install id" });
  const ref = db.collection("installs").doc(id);
  const snap = await ref.get();
  if (!snap.exists) await ref.set({ credits: FREE_CREDITS, created: FieldValue.serverTimestamp(), lastSeen: FieldValue.serverTimestamp() });
  else await ref.update({ lastSeen: FieldValue.serverTimestamp() });
  req.install = ref;
  next();
}

/** Atomically charge; 402 when the balance is gone. */
async function charge(ref, cost) {
  return db.runTransaction(async (tx) => {
    const s = await tx.get(ref);
    const bal = s.get("credits") ?? 0;
    if (bal < cost) return { ok: false, credits: bal };
    tx.update(ref, { credits: bal - cost });
    return { ok: true, credits: bal - cost };
  });
}

async function proxy(req, res, kind, build) {
  const c = await charge(req.install, COST[kind]);
  res.set("X-Mirage-Credits", String(c.credits));
  if (!c.ok) return res.status(402).json({ status: "OUT_OF_CREDITS", error_message: "Out of Mirage credits" });
  const { url, init } = build();
  const r = await fetch(url, init);
  res.status(r.status).type("application/json").send(await r.text());
}

app.get("/v1/credits", account, async (req, res) => {
  const s = await req.install.get();
  res.json({ credits: s.get("credits") ?? 0 });
});

// Same query string the app would send to Google (minus the key).
app.get("/v1/directions", account, (req, res) =>
  proxy(req, res, "directions", () => {
    const q = new URLSearchParams(req.query); q.set("key", KEY);
    return { url: `https://maps.googleapis.com/maps/api/directions/json?${q}`, init: {} };
  }));

app.get("/v1/geocode", account, (req, res) =>
  proxy(req, res, "geocode", () => {
    const q = new URLSearchParams(req.query); q.set("key", KEY);
    return { url: `https://maps.googleapis.com/maps/api/geocode/json?${q}`, init: {} };
  }));

app.post("/v1/places/searchText", account, (req, res) =>
  proxy(req, res, "places", () => ({
    url: "https://places.googleapis.com/v1/places:searchText",
    init: {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": KEY,
        "X-Goog-FieldMask": req.get("X-Goog-FieldMask") ?? "places.location,places.displayName,places.formattedAddress",
      },
      body: JSON.stringify(req.body ?? {}),
    },
  })));

/**
 * In-app purchase: the app sends the Play purchase token; we verify it with the Play
 * Developer API (service account with "View financial data" on the Play Console), credit
 * the install once, and acknowledge the purchase.
 */
app.post("/v1/purchase", account, async (req, res) => {
  const { productId, purchaseToken } = req.body ?? {};
  const credits = PACKS[productId];
  if (!credits || !purchaseToken) return res.status(400).json({ error: "unknown product or missing token" });
  const seen = db.collection("purchases").doc(purchaseToken);
  if ((await seen.get()).exists) return res.status(409).json({ error: "already redeemed" });

  const auth = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
  const play = google.androidpublisher({ version: "v3", auth });
  const p = await play.purchases.products.get({ packageName: PLAY_PACKAGE, productId, token: purchaseToken });
  if (p.data.purchaseState !== 0) return res.status(402).json({ error: "purchase not completed" });

  await db.runTransaction(async (tx) => {
    tx.set(seen, { installId: req.install.id, productId, credits, ts: FieldValue.serverTimestamp() });
    tx.update(req.install, { credits: FieldValue.increment(credits) });
  });
  if (p.data.acknowledgementState === 0) {
    await play.purchases.products.acknowledge({ packageName: PLAY_PACKAGE, productId, token: purchaseToken });
  }
  const s = await req.install.get();
  res.set("X-Mirage-Credits", String(s.get("credits"))).json({ credits: s.get("credits") });
});

app.get("/healthz", (_req, res) => res.send("ok"));

const port = process.env.PORT || 8080;
app.listen(port, () => console.log(`mirage-gateway listening on ${port}`));
