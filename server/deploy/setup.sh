#!/usr/bin/env bash
#
# One-shot provisioning for the intercom server on a fresh Oracle Cloud
# Always Free VM (Ubuntu 22.04 or 24.04, AMD micro shape).
#
#   sudo PUBLIC_HOST=yourname.duckdns.org EMAIL=you@example.com ./setup.sh
#
# Re-running is safe: it will not regenerate secrets that already exist.

set -euo pipefail

PUBLIC_HOST="${PUBLIC_HOST:-}"
EMAIL="${EMAIL:-}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo -e "\n\033[1;36m==> $*\033[0m"; }

[[ $EUID -eq 0 ]] || die "Run with sudo."
[[ -n "$PUBLIC_HOST" ]] || die "Set PUBLIC_HOST, e.g. PUBLIC_HOST=yourname.duckdns.org"
[[ -n "$EMAIL" ]] || die "Set EMAIL (Let's Encrypt expiry notices go here)"

# ---------------------------------------------------------------------------
info "Checking DNS"
# ---------------------------------------------------------------------------
PUBLIC_IP="$(curl -fsS --max-time 10 https://api.ipify.org || true)"
[[ -n "$PUBLIC_IP" ]] || die "Could not determine this VM's public IP"
PRIVATE_IP="$(ip -4 route get 1.1.1.1 | awk '{print $7; exit}')"
RESOLVED="$(getent hosts "$PUBLIC_HOST" | awk '{print $1; exit}' || true)"

echo "  public IP : $PUBLIC_IP"
echo "  private IP: $PRIVATE_IP"
echo "  $PUBLIC_HOST -> ${RESOLVED:-<unresolved>}"

if [[ "$RESOLVED" != "$PUBLIC_IP" ]]; then
  die "$PUBLIC_HOST does not point at $PUBLIC_IP.
     Fix the DNS record first (on DuckDNS, set the IP and wait a minute).
     Let's Encrypt will fail otherwise."
fi

# ---------------------------------------------------------------------------
info "Installing packages"
# ---------------------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg coturn certbot iptables-persistent jq

if ! command -v node >/dev/null || [[ "$(node -v | cut -c2- | cut -d. -f1)" -lt 20 ]]; then
  info "Installing Node.js 20"
  mkdir -p /etc/apt/keyrings
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
    | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
  echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
    > /etc/apt/sources.list.d/nodesource.list
  apt-get update -qq
  apt-get install -y -qq nodejs
fi
echo "  node $(node -v)"

# ---------------------------------------------------------------------------
info "Creating user and directories"
# ---------------------------------------------------------------------------
id -u intercom >/dev/null 2>&1 || useradd --system --home /opt/intercom --shell /usr/sbin/nologin intercom
install -d -o intercom -g intercom /var/lib/intercom
install -d -m 750 -o root -g intercom /etc/intercom
install -d -o intercom -g intercom /opt/intercom

# ---------------------------------------------------------------------------
info "Opening firewall ports"
# ---------------------------------------------------------------------------
# Oracle's Ubuntu images ship a restrictive INPUT chain. This is the single most
# common reason a freshly built VM appears dead from the internet.
add_rule() {
  local proto=$1 port=$2
  iptables -C INPUT -p "$proto" --dport "$port" -j ACCEPT 2>/dev/null \
    || iptables -I INPUT 5 -p "$proto" --dport "$port" -j ACCEPT
}
add_rule tcp 443     # signalling (WSS) + Let's Encrypt HTTP-01 redirect target
add_rule tcp 80      # Let's Encrypt HTTP-01 challenge
add_rule tcp 3478    # TURN over TCP
add_rule udp 3478    # TURN over UDP (the fast path)
add_rule tcp 5349    # TURN over TLS
add_rule udp 5349
iptables -C INPUT -p udp --dport 49160:49360 -j ACCEPT 2>/dev/null \
  || iptables -I INPUT 5 -p udp --dport 49160:49360 -j ACCEPT   # relayed media
netfilter-persistent save >/dev/null

cat <<'EOF'

  !! Firewall rules are saved on the VM, but Oracle ALSO has a cloud-side
     firewall. In the Oracle console go to:
       Networking -> Virtual Cloud Networks -> your VCN -> Security Lists
     and add ingress rules from 0.0.0.0/0 for:
       TCP  80, 443, 3478, 5349
       UDP  3478, 5349, 49160-49360
     Nothing below will work from the internet until you do this.

EOF

# ---------------------------------------------------------------------------
info "Obtaining TLS certificate"
# ---------------------------------------------------------------------------
if [[ ! -d "/etc/letsencrypt/live/$PUBLIC_HOST" ]]; then
  certbot certonly --standalone --non-interactive --agree-tos \
    -m "$EMAIL" -d "$PUBLIC_HOST" \
    || die "certbot failed. Check that port 80 is reachable (Oracle Security List!)."
else
  echo "  certificate already present"
fi

# Both coturn and the node server need to read the key.
groupadd -f ssl-cert
chgrp -R ssl-cert /etc/letsencrypt/live /etc/letsencrypt/archive
chmod -R g+rX /etc/letsencrypt/live /etc/letsencrypt/archive
usermod -aG ssl-cert intercom
usermod -aG ssl-cert turnserver 2>/dev/null || true

# Reload both services after each renewal, or calls break every 90 days.
cat > /etc/letsencrypt/renewal-hooks/deploy/intercom-reload.sh <<'HOOK'
#!/bin/sh
chgrp -R ssl-cert /etc/letsencrypt/live /etc/letsencrypt/archive
chmod -R g+rX /etc/letsencrypt/live /etc/letsencrypt/archive
systemctl restart coturn intercom
HOOK
chmod +x /etc/letsencrypt/renewal-hooks/deploy/intercom-reload.sh

# ---------------------------------------------------------------------------
info "Generating secrets"
# ---------------------------------------------------------------------------
ENV_FILE=/etc/intercom/intercom.env
if [[ -f "$ENV_FILE" ]]; then
  echo "  reusing existing secrets in $ENV_FILE"
  # shellcheck disable=SC1090
  source "$ENV_FILE"
else
  HOME_SECRET="$(openssl rand -hex 32)"
  TURN_SECRET="$(openssl rand -hex 32)"
  cat > "$ENV_FILE" <<EOF
PORT=443
PUBLIC_HOST=$PUBLIC_HOST
HOME_SECRET=$HOME_SECRET
TURN_SECRET=$TURN_SECRET
STORE_PATH=/var/lib/intercom/devices.json
SSL_CERT=/etc/letsencrypt/live/$PUBLIC_HOST/fullchain.pem
SSL_KEY=/etc/letsencrypt/live/$PUBLIC_HOST/privkey.pem
FCM_SERVICE_ACCOUNT=/etc/intercom/fcm-service-account.json
EOF
  chmod 640 "$ENV_FILE"
  chgrp intercom "$ENV_FILE"
fi

# ---------------------------------------------------------------------------
info "Configuring coturn"
# ---------------------------------------------------------------------------
sed -e "s|__PRIVATE_IP__|$PRIVATE_IP|g" \
    -e "s|__PUBLIC_IP__|$PUBLIC_IP|g" \
    -e "s|__PUBLIC_HOST__|$PUBLIC_HOST|g" \
    -e "s|__TURN_SECRET__|$TURN_SECRET|g" \
    "$REPO_DIR/deploy/turnserver.conf" > /etc/turnserver.conf
chmod 640 /etc/turnserver.conf
chgrp turnserver /etc/turnserver.conf 2>/dev/null || true
sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn 2>/dev/null || true

# ---------------------------------------------------------------------------
info "Installing the signalling server"
# ---------------------------------------------------------------------------
rsync -a --delete --exclude node_modules "$REPO_DIR/" /opt/intercom/server/
cd /opt/intercom/server
npm install --omit=dev --no-audit --no-fund
chown -R intercom:intercom /opt/intercom

install -m 644 "$REPO_DIR/deploy/intercom.service" /etc/systemd/system/intercom.service
systemctl daemon-reload
systemctl enable --now coturn intercom
sleep 2

# ---------------------------------------------------------------------------
info "Done"
# ---------------------------------------------------------------------------
systemctl --no-pager --lines=0 status coturn intercom || true

echo
echo "  Health check:  curl https://$PUBLIC_HOST/health"
echo
echo "  Put these into the apps' setup screens:"
echo "    Server URL   wss://$PUBLIC_HOST/ws"
echo "    Home secret  $(grep '^HOME_SECRET=' "$ENV_FILE" | cut -d= -f2)"
echo
echo "  Still to do: copy your Firebase service account JSON to"
echo "    /etc/intercom/fcm-service-account.json   (chmod 640, chgrp intercom)"
echo "  then: sudo systemctl restart intercom"
echo "  Without it, phones only ring when their app is already open."
echo
