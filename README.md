# Pioneer BT Stereo (Android)

Your `pioneer.html` stereo wrapped in an Android app so it can use real
Bluetooth, phone calls and system volume. (A web page alone cannot answer
calls or touch Bluetooth audio - browsers don't allow it.)

## Build
1. Install Android Studio and open this folder (File > Open > PioneerBT).
2. Let Gradle sync (if asked for a Gradle version, choose 8.7 or newer).
3. Plug in your phone (USB debugging on) and press Run,
   or Build > Build APK(s) and install the APK.
4. On first launch, allow the permissions (Phone, Call log, Contacts, Nearby devices).

## How it works
- Bluetooth: pair/connect your car or speaker in Android as usual. The BT
  button on the faceplate opens Bluetooth settings; the connected device name
  shows on the LCD. Music goes out over Bluetooth automatically.
- Volume: the knob sets the real Android volume (music, or call volume during
  a call). Phone volume keys stay in sync with the knob.
- Bass / Treble / Balance: push the knob to cycle VOL > BASS > TREBLE > BAL,
  turn to adjust. These apply to music played inside this app (USB ADD).
  Android does not let one app equalize Spotify or other apps.
- Phone calls:
  - Incoming: LCD shows the name/number. Tap CALL = answer, hold CALL = reject.
  - During a call: tap CALL = hang up, MODE = mute mic, knob = call volume.
    Music pauses and resumes after the call. Call audio goes through your
    Bluetooth headset/car automatically.
  - Outgoing: tap CALL when idle for the dial pad, then press CALL on it.
- SPOTIFY button opens the Spotify app.

## Notes
- Needs Android 9 or newer.
- Not compiled or tested on a device by the author (no Android SDK available
  when this was generated) - if Gradle reports an error, send it over.
- Number/name on incoming calls needs the Call log and Contacts permissions.
- Mic mute depends on the phone maker; some phones ignore it.
- The folder picker is not supported by Android WebView; pick files instead.
