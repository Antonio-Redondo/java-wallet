#!/usr/bin/env bash
#
# End-to-end demo of the wallet API using only curl + sed.
# Start the server first:  mvn spring-boot:run
#
# The API is OAuth2-protected, so this first fetches a JWT from /oauth/token
# (client-credentials grant) and sends it as a Bearer token on every call.
#
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
CLIENT_ID="${CLIENT_ID:-demo-client}"
CLIENT_SECRET="${CLIENT_SECRET:-demo-secret}"

id_of()    { sed -E 's/.*"id":"([^"]+)".*/\1/'; }
token_of() { sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }

echo "Requesting an OAuth2 access token (client-credentials grant)..."
TOKEN=$(curl -s -X POST "$BASE/oauth/token" \
  -d grant_type=client_credentials \
  -d "client_id=$CLIENT_ID" \
  -d "client_secret=$CLIENT_SECRET" | token_of)
if [ -z "$TOKEN" ]; then
  echo "  Failed to obtain a token - is the server running and the client configured?" >&2
  exit 1
fi
echo "  token acquired (${#TOKEN} chars)"
AUTH="Authorization: Bearer $TOKEN"

echo "Creating two EUR accounts..."
A=$(curl -s -X POST "$BASE/accounts" -H "$AUTH" -H 'Content-Type: application/json' -d '{"currency":"EUR"}' | id_of)
B=$(curl -s -X POST "$BASE/accounts" -H "$AUTH" -H 'Content-Type: application/json' -d '{"currency":"EUR"}' | id_of)
echo "  A = $A"
echo "  B = $B"

echo "Depositing 100.00 into A..."
curl -s -X POST "$BASE/transfers" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"toAccountId\":\"$A\",\"amount\":100.00,\"currency\":\"EUR\"}" > /dev/null

echo "Transferring 30.00 from A to B (idempotent)..."
curl -s -X POST "$BASE/transfers" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"demo-1\",\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":30.00,\"currency\":\"EUR\"}" > /dev/null

echo "Repeating the same transfer with the same key (should NOT move money again)..."
curl -s -X POST "$BASE/transfers" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"demo-1\",\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":30.00,\"currency\":\"EUR\"}" > /dev/null

echo
echo "Balance A (expect 70.0000):"
curl -s "$BASE/accounts/$A/balance" -H "$AUTH"; echo
echo "Balance B (expect 30.0000):"
curl -s "$BASE/accounts/$B/balance" -H "$AUTH"; echo
echo
echo "Ledger for A:"
curl -s "$BASE/accounts/$A/transactions" -H "$AUTH"; echo
