#!/usr/bin/env bash
# ADB over Wi-Fi, to look after a mounted tablet without a cable.
#
#   tools/adb-wifi.sh enable    tablet on USB: switch its ADB to Wi-Fi, then connect
#   tools/adb-wifi.sh connect   connect over Wi-Fi (needs FRAME_IP=<tablet address>)
#   tools/adb-wifi.sh status    is it reachable, and how (needs FRAME_IP)
#
# Fire OS 5 forgets the Wi-Fi setting when the tablet restarts; then "enable"
# needs the USB cable once more. The tablet's address is shown by tapping a
# top corner of the frame.
set -euo pipefail
IP="${FRAME_IP:-}"
PORT=5555
need_ip() { [ -n "$IP" ] || { echo "Set FRAME_IP to the tablet's address, e.g. FRAME_IP=192.168.1.50 $0 $1"; exit 1; }; }

case "${1:-status}" in
  enable)
    adb -d get-state >/dev/null 2>&1 || { echo "Plug the tablet in over USB first."; exit 1; }
    IP=$(adb -d shell ip -f inet addr show wlan0 | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1)
    adb -d tcpip "$PORT"
    # The tablet's ADB restarts; an early connect can hang, so retry with a time limit.
    for _ in 1 2 3 4 5 6; do
      sleep 3
      timeout 15 adb connect "$IP:$PORT" | grep -q "connected to" && break
    done
    echo "ADB over Wi-Fi is on at $IP:$PORT until the tablet restarts. The USB cable can come out."
    ;;
  connect)
    need_ip connect
    timeout 20 adb connect "$IP:$PORT"
    ;;
  status)
    need_ip status
    adb devices -l
    if curl -s -m 5 -o /dev/null "http://$IP:8080/api/settings"; then
      echo "FreeFrame answers at http://$IP:8080/"
    else
      echo "FreeFrame does not answer at $IP (tablet off, asleep, or at another address?)"
    fi
    ;;
  *)
    echo "usage: $0 enable|connect|status"; exit 2
    ;;
esac
