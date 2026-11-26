# Ride test: Ronin Dash 0.5

Everything is recorded in the ride log on its own. When something odd happens, open the Test tab and tap **Mark now** (add a note if you can).
After the ride, plug the phone in and Claude pulls the logs.

## Before the ride (at home, bike off)

1. Open Ronin Dash. Watch the opening animation (tap to skip it).
2. Setup, step by step:
   - Allow every permission (choose **Allow all the time** for location).
   - Xiaomi: App info → **Autostart on**, Battery saver → **No restrictions**.
3. TVS Connect: **Force stop** it (App info → Force stop), so it doesn't fight over the bike.
4. Settings → Sounds in your helmet → **Hear the sounds**. Play a few through the Q8. Pick a voice.
5. Settings → **Petrol ₹/L** and **Tank (L)** correct?

## Cold start (needs the bike cold: after it has stood 3+ hours)

6. Ignition on. Android's "allow access to your bike" dialog (setup step 4, or Settings → Bike → **Set up**). Type the code from the bike if it asks.
7. Settings → Bike should say **Let Android wake the app: On**.
8. Start the engine. Check:
   - the bike shows **COLD ENGINE** for a few seconds
   - the dashboard shows the snowflake and a warm-up bar
   - the Ride tab shows the **Cold start** card with a start temperature
9. Ride gently. At about 60 °C (~9 min) a **soft chime** should play in the helmet, and the bar goes away.

## Riding

10. **Phone on the mount**: the dashboard should open by itself after connecting. In your pocket it should not.
11. **Screens**: parked = everything. Riding = one big number + next turn. Above 70 km/h = only a glow. Swipe up = everything for 10 s.
12. **Indicators**: left and right ticks sound different. Leave one on for 30+ s; you should get a nudge sound. Try the hazard.
13. **Google Maps navigation** to somewhere a few km away:
    - a tone ~300 m before each turn, another at ~50 m
    - turns only appear on the bike in the last ~1 km
14. **Calls/messages**: get someone to call and to WhatsApp you while you're above 20 km/h.
    - The bike should not show them. You should hear one chirp each when you slow down.
15. **Speed alert**: go above the limit (default 80 km/h) for 3+ s. You should hear a sound and the phone should vibrate.
16. **Side stand**: (stopped, safely) put the stand down with the engine on. Do you hear the alarm and "Side stand"?
17. **Bike display brightness**: should change with the light (e.g. riding into shade or a tunnel). Note it if it flickers.
    Also try changing it by hand on the bike once; after that the app should leave it alone.

## Fuel (if you refuel on this ride)

18. After filling, the **Filled up?** card should appear. Answer it (Full tank / Litres / ₹).
19. The Ride tab: **Fuel** in litres, **Range**, **Mileage now** (coloured), **Trip average**.
    Do they look believable against the bike's gauge?

## Ending the ride

20. Ignition off. The app should save the trip and **go away by itself**, with no notification left behind.
21. Short stop (under 45 min, e.g. tea) and ride on: it should be **one trip**, not two.
22. Next morning, the bike off: is anything from Ronin Dash running? (There shouldn't be.)

## Write down

- Anything that sounded wrong, too loud or too frequent.
- Any number that showed "–" or looked silly.
- Did the app ever not connect, and how long it took.
- Test tab line at the end of the ride: memory, battery used this ride, anything in red.

## Getting the logs (Claude does this)

```bash
adb pull /sdcard/Android/data/dev.ronindash/files/logs ./captures/phone_logs
adb pull /sdcard/Android/data/dev.ronindash/files/trips ./captures/phone_trips
```
