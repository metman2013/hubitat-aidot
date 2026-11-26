# hubitat-aidot

Local (LAN-only) Hubitat driver for AiDot / Linkind Wi-Fi bulbs. Hubitat talks to the bulb directly on TCP port 10000. There's no cloud in the loop once it's set up.

The protocol is ported from [python-aidot](https://pypi.org/project/python-aidot/), the library behind Home Assistant's AiDot integration.

## Files

| File | What it's for |
|---|---|
| `AiDot_WiFi_Bulb_Local.groovy` | The Hubitat driver |
| `aidot_get_keys.py` | One-time script that pulls each bulb's local keys from your AiDot account |

## Setup

### 1. Get the bulb's keys (one time)

Each bulb encrypts its local traffic with a key that only the AiDot cloud knows. Run this once to fetch it:

```bash
pip install python-aidot
python3 aidot_get_keys.py
```

Run it from a machine on the same network/VLAN as the bulb so the "discovered IP" shows up. Re-run it if you ever re-pair the bulb in the AiDot app, because re-pairing generates a new key.

### 2. Install the driver

1. Hubitat → **Drivers Code** → **New Driver** → **Import**, and paste:
   ```
   https://raw.githubusercontent.com/metman2013/hubitat-aidot/main/AiDot_WiFi_Bulb_Local.groovy
   ```
   Then click **Save**.
2. **Devices** → **Add Device** → **Virtual**, and choose type **AiDot WiFi Bulb (Local)**.
3. Fill in the preferences from the script's output, then click **Save**. The `connection` attribute should change to `online`.

### 3. Network

- Give the bulb a DHCP reservation. The driver connects to a fixed IP.
- If the bulb is on an IoT VLAN, allow Hubitat to reach it on TCP 10000.

## Updating

Drivers Code → *AiDot WiFi Bulb (Local)* → **Import** → **Import** (the URL is pre-filled from the driver's `importUrl`) → **Save**.

## Supported

On/off, level, color temperature, RGB color, refresh, and push updates from the bulb (changes made in the AiDot app show up in Hubitat). Light effects/scenes aren't supported; AiDot runs those through its cloud.

## Troubleshooting

Turn on debug logging and watch `TX:` and `RX:` in the logs.

- **"bulb rejected login"**: the Device ID, User ID, or password is wrong.
- **"could not decrypt frame"**: the AES key is wrong.
- **Stuck on `connecting`**: wrong IP, or the firewall is blocking TCP 10000.
