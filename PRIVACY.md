# Finora — privacy and permissions

Finora is a personal finance app that keeps your financial life on your phone. There is no
account, no Finora server, no cloud sync, no analytics or advertising SDK, and no AI service.

## What stays on the phone

| Data | Where it lives | Leaves the phone? |
|---|---|---|
| Transactions, accounts, categories, merchants, people | Finora's private Room/SQLite database | Never |
| SMS messages | Read by the on-device parser; the text of a parsed transaction message is kept with that transaction so you can see where it came from | Never |
| Loans, bill splits, investments, prices you entered | Same database | Never |
| Documents (receipts, agreements, statements) | `files/vault/`, each file encrypted with AES-256-GCM | Never |
| Receipt photos for OCR | Read by ML Kit's **bundled** text-recognition model, in the app | Never |
| Settings, reminder times | Same database | Never |
| Backups | A file you choose with the system picker, encrypted with your password | Only where *you* put it |

All calculations — totals, interest, EMI schedules, portfolio values, analytics, projections —
are done locally and work with no network at all.

## What goes online, and only when you ask

1. **Merchant lookup** (off by default). When you press *Search nearby* for a merchant Finora
   has never seen, it sends the merchant name — and, if you allowed location, your approximate
   coordinates — to the place-search server set in Settings (OpenStreetMap Nominatim by
   default). Nothing financial is sent: no amount, no date, no account.
   - At most one request per second; results are cached for 30 days, so the same search is not
     repeated; a merchant you have already confirmed is never looked up again.
   - The request carries an identifying User-Agent, as the
     [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/) requires.
     Autocomplete, bulk and periodic queries — which that policy forbids — are never made.
   - The server is configurable, so it can be pointed at a self-hosted Nominatim, or lookups
     switched off entirely, without an app update.
2. **Map preview** (off by default). Shows a small map of a candidate place. Map tiles for that
   view are downloaded from `tile.openstreetmap.org` under the
   [tile usage policy](https://operations.osmfoundation.org/policies/tiles/): identifying
   User-Agent, on-screen "© OpenStreetMap contributors", tiles cached, no pre-fetching or
   offline download.

No paid API and no API key are needed for either. If either is unavailable, you enter the
merchant yourself and everything else carries on.

## Permissions

| Permission | Why | When it is asked | If you refuse |
|---|---|---|---|
| `RECEIVE_SMS`, `READ_SMS` | Record bank/card/UPI transactions automatically; import past messages | Only when you turn on SMS capture or press Import | Manual entry and CSV import still work |
| `ACCESS_COARSE_LOCATION` | Sort nearby businesses by distance during a merchant search | Only when you turn on location and press Search | Search runs without distance, or you enter the merchant |
| `ACCESS_FINE_LOCATION` | Same, if you choose *Precise location* | Only if you enable it | Approximate location is used |
| `POST_NOTIFICATIONS` | Daily review and portfolio reminders | From the Reminders settings | No reminders appear |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Merchant lookup and map preview only | Install time (not a runtime permission) | — |

Finora never requests background location, never tracks movement, and keeps no location
history. A location is read once for a search you started and then discarded. Being near a
business is shown as a hint, never treated as proof that you paid there.

`CAMERA` is **not** requested: receipt photos are taken by your camera app through the system
"take picture" intent.

> Google Play restricts SMS permissions to default SMS apps and a short list of exceptions.
> Finora is built for personal, side-loaded use. Publishing it on Play would require either a
> Play policy exception or removing automatic SMS capture (manual entry and CSV import remain).

## Encryption — exactly what is and is not protected

- **Vault documents**: AES-256-GCM with a key generated inside the Android Keystore that never
  leaves it. Uninstalling Finora or clearing its data destroys the key, and those files can then
  only be recovered from a backup.
- **Backups**: AES-256-GCM with a key derived from your password (PBKDF2-HMAC-SHA256, 210,000
  iterations, random salt). The password is not stored. **A forgotten password cannot be
  recovered by anyone.**
- **The database is not separately encrypted by Finora.** It is protected by Android's
  per-app sandbox and, on a phone with a screen lock, by the device's own file-based
  encryption. Finora does not claim more than that.
- Android's automatic cloud backup and device-to-device transfer are disabled for Finora
  (`data_extraction_rules.xml`), so your data is never copied to Google Drive without you
  knowing. Use Finora's own encrypted backup.

## Data loss

Because nothing is stored in the cloud, **losing or resetting the phone, or uninstalling
Finora, without a backup loses the data permanently.** Make a backup regularly (Settings ›
Backup) and keep the file somewhere safe.
