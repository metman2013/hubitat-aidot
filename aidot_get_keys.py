#!/usr/bin/env python3
"""
One-time helper: log into your AiDot account and print the local-control
values (device id, user id, device password, AES key, IP) that the Hubitat
"AiDot WiFi Bulb (Local)" driver needs.

After this runs once, Hubitat talks to the bulb directly over your LAN; the
cloud is not used again unless you re-pair the bulb in the AiDot app (which
generates a new key - just re-run this script).

Requirements:  pip install python-aidot
Run:           python3 aidot_get_keys.py

Run it from a machine on the same network/VLAN as the bulb if you want the
"discovered IP" column filled in (UDP broadcast on port 6666).
"""
import asyncio
import getpass
import json

import aiohttp
from aidot.client import AidotClient, rsa_password_encrypt
from aidot.login_const import APP_ID


async def main() -> None:
    username = input("AiDot account email: ").strip()
    password = getpass.getpass("AiDot password: ")
    country = (input("Country code [US]: ").strip() or "US").upper()

    async with aiohttp.ClientSession() as session:
        client = AidotClient(session, country, username, password)

        # Same request the library makes, but show the server's reason on failure
        url = f"{client._base_url}/users/loginWithFreeVerification"
        body = {
            "countryKey": f"region:{client.country_name.strip()}",
            "username": username,
            "password": rsa_password_encrypt(password),
            "terminalId": await client.get_terminal_id(),
            "webVersion": "0.5.0",
            "area": "Asia/Shanghai",
            "UTC": "UTC+8",
        }
        resp = await session.post(url, headers={"Appid": APP_ID, "Terminal": "app"}, json=body)
        login = await resp.json(content_type=None)
        if resp.status != 200 or "id" not in login:
            print(f"\nLogin failed (HTTP {resp.status}). Server said:\n  {login}")
            print("\nCommon causes:")
            print("  - You sign into the AiDot app with Google/Apple/Facebook: set a password")
            print("    in the app (Me > account > set/change password) and use that here.")
            print("  - Wrong country: use the country picked when the account was created.")
            print("  - Mistyped email/password (the password prompt doesn't echo).")
            return
        login["password"] = password
        login["region"] = client._region
        login["country"] = client.country_name
        client.login_info = login
        client.setup_discover()
        user_id = login["id"]
        data = await client.async_get_all_device()

        # Give LAN discovery a few seconds to hear back from the bulbs
        print("\nLogged in. Listening for bulbs on the LAN for 10 seconds...")
        await asyncio.sleep(10)
        discovered = client._discover.discovered_device if client._discover else {}

        print("\n================ Paste these into the Hubitat device ================")
        for dev in data["device_list"]:
            aes = (dev.get("aesKey") or [None])[0]
            if not aes:
                print(f"\n[{dev.get('name')}] has no local AES key - local control not supported.")
                continue
            modules = [m.get("identity") for m in dev.get("product", {}).get("serviceModules", [])]
            cct = next(
                (m["properties"][0] for m in dev.get("product", {}).get("serviceModules", [])
                 if m.get("identity") == "control.light.cct"),
                None,
            )
            info = {
                "Name": dev.get("name"),
                "Bulb IP (cloud)": (dev.get("properties") or {}).get("ipAddress"),
                "Bulb IP (discovered)": discovered.get(dev.get("id")),
                "MAC": dev.get("mac"),
                "Device ID": dev.get("id"),
                "User ID": user_id,
                "Device password": dev.get("password"),
                "AES key": aes,
                "Model ID": dev.get("modelId"),
                "simpleVersion": dev.get("simpleVersion"),
                "Supports color (RGBW)": "control.light.rgbw" in modules,
                "Supports color temp": cct is not None or "control.light.rgbw" in modules,
                "CT min/max": f"{cct['minValue']}-{cct['maxValue']}" if cct else None,
            }
            print()
            for k, v in info.items():
                print(f"  {k:24} {v}")
        print("\n=====================================================================")
        print("Tip: set a DHCP reservation for the bulb's MAC so its IP never changes.")

        await client.async_close()


if __name__ == "__main__":
    asyncio.run(main())
