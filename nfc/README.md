# NFC Reader

Android app that reads NFC cards and tags (access cards, transport cards, hotel keys, NFC stickers,
NTAG / MIFARE / ISO 15693 tags...) and lets you copy what it reads.

## Install

Download [`apk/NfcReader.apk`](../apk/NfcReader.apk) on the phone and open it. Android will ask you to allow
"install unknown apps" for the app you opened it from (Chrome, Files...). NFC must be on.

## Use

Open the app and hold a card against the back of the phone (near the camera) for a second.

- **UID**: the card's serial number, in hex. Tap it or press **Copy UID** to copy it.
  The card info section also shows it in decimal (normal and reversed byte order), the format many
  access-control systems print.
- **Copy all** copies everything; each section has its own **Copy**, and any text can be selected by hand.
- **Content (NDEF)**: text, links or other data stored on the tag.
- **Memory**: page / block dump. MIFARE Classic sectors are read with the well-known default keys;
  sectors with private keys show as locked. Smart cards (ISO 14443-4) protect their data, so only the
  UID and chip info are shown.
- **Write to tag**: copies the NDEF content (text, link...) onto another blank/writable tag.
- **History**: every scan is kept on the phone. Tap to reopen, long-press to delete.

Phones cannot change a card's UID, so the UID itself cannot be cloned onto another card.
Some phones (non-NXP NFC chips) cannot read MIFARE Classic sectors; the UID still works.

The app is in Greek on Greek phones and in English otherwise.

## Build

```sh
nfc/build.sh   # -> build/NfcReader.apk
```

Same SDK-free toolchain and signing key as the Timetable app (see the main README).
