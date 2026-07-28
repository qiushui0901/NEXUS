#!/usr/bin/env sh
set -eu

: "${NEXUS_MCP_URL:?Set NEXUS_MCP_URL to the NEXUS /mcp endpoint}"
: "${NEXUS_API_KEY:?Set NEXUS_API_KEY without writing it into this script}"

case "$NEXUS_MCP_URL" in
  http://*)
    exec npx -y mcp-remote@0.1.38 "$NEXUS_MCP_URL" \
      --header 'X-API-Key:${NEXUS_API_KEY}' \
      --transport http-only \
      --allow-http
    ;;
  https://*)
    exec npx -y mcp-remote@0.1.38 "$NEXUS_MCP_URL" \
      --header 'X-API-Key:${NEXUS_API_KEY}' \
      --transport http-only
    ;;
  *)
    echo "NEXUS_MCP_URL must start with http:// or https://" >&2
    exit 2
    ;;
esac
