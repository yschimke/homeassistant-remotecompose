#!/bin/sh
# Starts nginx on the ingress port, then the design server, which starts its bundled UI Builder.
# Both servers listen on loopback only; nginx is the one way in.
set -eu

# UI Builder's operator token: made once and kept, so its links survive a restart. Only the
# design server and nginx hold it; no browser ever sees it.
token_file=/data/operator-token
if [ ! -s "$token_file" ]; then
  (umask 077 && head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$token_file")
fi
token=$(cat "$token_file")

# Where nginx checks a Home Assistant access token: Core, on the port and scheme it serves.
if [ -z "${CORE_URL:-}" ]; then
  info=$(curl -fsS -H "Authorization: Bearer $SUPERVISOR_TOKEN" http://supervisor/core/info)
  port=$(printf '%s' "$info" | jq -r '.data.port')
  scheme=$(printf '%s' "$info" | jq -r 'if .data.ssl then "https" else "http" end')
  CORE_URL="$scheme://homeassistant:$port"
fi

sed -e "s/@OPERATOR_TOKEN@/$token/" -e "s#@CORE_URL@#$CORE_URL#" \
  /etc/nginx/card-designs.conf.template > /etc/nginx/conf.d/card-designs.conf
nginx

export UI_BUILDER_TOKEN="$token"
export HOST=127.0.0.1 PORT=8080
export UI_BUILDER_HOST=127.0.0.1 UI_BUILDER_PORT=8724
# Editor links are root-relative; nginx adds the ingress prefix.
export UI_BUILDER_BROWSER_URL=
# The public library follows ha.coo.ee's; the image's is only where it starts.
export LIBRARY_UPSTREAM=https://ha.coo.ee
# Who is signed in is Home Assistant's to say (design-server's GithubAuth.SignIn.Proxy).
export TRUSTED_USER_HEADER=X-Remote-User-Name
exec java -cp '/app/lib/*' ee.schimke.ha.designserver.MainKt
