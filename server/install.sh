#!/usr/bin/env bash
# Установка сервера «Рубеж» на Ubuntu/Debian одной командой:
#   curl -fsSL https://raw.githubusercontent.com/TRO9II/lbs-kml/main/server/install.sh | sudo bash
set -euo pipefail
PORT="${PORT:-8080}"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) BIN=rubezh-server-linux-amd64 ;;
  aarch64|arm64) BIN=rubezh-server-linux-arm64 ;;
  *) echo "Неизвестная архитектура: $ARCH"; exit 1 ;;
esac
echo "▶ Скачиваю сервер ($BIN)…"
curl -fsSL -o /usr/local/bin/rubezh-server.new "https://github.com/TRO9II/lbs-kml/releases/download/server/$BIN"
chmod +x /usr/local/bin/rubezh-server.new
mv -f /usr/local/bin/rubezh-server.new /usr/local/bin/rubezh-server   # замена работающего файла — без ошибки «Text file busy»
id rubezh >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin rubezh
mkdir -p /var/lib/rubezh && chown rubezh:rubezh /var/lib/rubezh && chmod 700 /var/lib/rubezh
cat > /etc/systemd/system/rubezh.service <<UNIT
[Unit]
Description=Rubezh relay server
After=network-online.target
Wants=network-online.target

[Service]
User=rubezh
ExecStart=/usr/local/bin/rubezh-server -addr :$PORT -data /var/lib/rubezh
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/rubezh
ProtectHome=true

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable rubezh >/dev/null 2>&1
systemctl restart rubezh   # при повторном запуске — обновление до новой версии
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow "$PORT"/tcp >/dev/null
fi
# автообновление: раз в час проверяем новую версию на GitHub
cat > /usr/local/bin/rubezh-update <<'UPD'
#!/usr/bin/env bash
set -eu
case "$(uname -m)" in x86_64|amd64) BIN=rubezh-server-linux-amd64 ;; *) BIN=rubezh-server-linux-arm64 ;; esac
TMP=/usr/local/bin/rubezh-server.new
curl -fsSL -o "$TMP" "https://github.com/TRO9II/lbs-kml/releases/download/server/$BIN" || exit 0
chmod +x "$TMP"
if cmp -s "$TMP" /usr/local/bin/rubezh-server; then rm -f "$TMP"; exit 0; fi
mv -f "$TMP" /usr/local/bin/rubezh-server
systemctl restart rubezh
echo "rubezh: обновлён $(date)"
UPD
chmod +x /usr/local/bin/rubezh-update
cat > /etc/systemd/system/rubezh-update.service <<'UNIT'
[Unit]
Description=Rubezh server auto-update
[Service]
Type=oneshot
ExecStart=/usr/local/bin/rubezh-update
UNIT
cat > /etc/systemd/system/rubezh-update.timer <<'UNIT'
[Unit]
Description=Rubezh server auto-update (hourly)
[Timer]
OnBootSec=5min
OnUnitActiveSec=1h
RandomizedDelaySec=10min
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now rubezh-update.timer >/dev/null 2>&1

sleep 1
IP="$(curl -fsS https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
if curl -fsS "http://127.0.0.1:$PORT/api/ping" >/dev/null; then
  echo
  echo "✅ Сервер «Рубеж» работает."
  echo "   Адрес для приложения:  http://$IP:$PORT"
  echo "   (Меню → Чат → Создать группу → вставьте этот адрес)"
  echo "   Автообновление включено: сервер раз в час сам ставит новую версию."
else
  echo "❌ Сервер не ответил. Посмотрите журнал: journalctl -u rubezh -n 50"
  exit 1
fi
