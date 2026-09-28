# CCloud API Relay

If OMDb can't be reached (e.g. from Iran), this small server runs on a VPS outside Iran and forwards the app's OMDb requests:

- The OMDb keys stay on the server; the app only knows the relay's address
- Only lookups by IMDb id are forwarded
- Answers are cached for 30 days, shared by all users, which saves OMDb's daily limit
- OMDb keys are used in turn when one reaches its daily limit
- Each user (IP) is limited to 120 requests a minute

## Setup

You need a VPS outside Iran with Docker, and a domain (or subdomain) for the relay.

1. **DNS**: add an `A` record for e.g. `api.example.com` pointing to the VPS IP.
   - Recommended: put the domain on Cloudflare with the proxy turned on (orange cloud) and SSL/TLS mode **Full (strict)**. The app then talks to Cloudflare and the VPS IP isn't in the app, so it can't be found and blocked (which would also block any VPN on the same server).
2. **Ports**: Caddy needs ports 80 and 443 for HTTPS. Check they're free: `sudo ss -tlnp | grep -E ':(80|443)\b'`. If 443 is taken (e.g. by your VPN), set `HTTPS_PORT=8443` in `.env` and use `https://api.example.com:8443` as the relay URL (8443 also works through Cloudflare).
3. **Configure**: copy this folder to the server, then:
   ```sh
   cp .env.example .env
   openssl rand -hex 16   # use the output as RELAY_TOKEN
   nano .env              # fill in RELAY_DOMAIN, RELAY_TOKEN and the OMDb keys
   ```
4. **Start**:
   ```sh
   docker compose up -d --build
   curl https://api.example.com/health   # {"status": "ok"}
   ```
5. **App**: add two repository secrets on GitHub (Settings → Secrets and variables → Actions):
   - `RELAY_URL`: `https://api.example.com`
   - `RELAY_TOKEN`: the same token as in `.env`

   Builds made after that send OMDb requests through the relay and no longer contain the OMDb keys.

Logs: `docker compose logs -f relay`. Update after changes: `docker compose up -d --build`.

## Tests

```sh
python3 -m unittest test_relay.py
```
