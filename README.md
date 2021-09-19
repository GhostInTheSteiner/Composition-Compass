# Composition Compass

This project allows you to download similar songs to a given input sample. You can then play those songs back and sort them out without even looking on your screen, which is especially useful while driving long distances in your car.

Composition Compass is based on an equally named Tasker-only project:

...

Contrary to the original Composition Compass it's successor consists of two separate applications: A downloader and a player. The downloader has been written in Kotlin and is provided as simple APK file. The player is still a Tasker project and therefore provided as XML.

Note #1: To use the player you need to purchase [Tasker for Android](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm).

Note #2: You **no longer need root** to use Composition Compass ;)

## Setup

The setup process has been greatly simplified, but you'll still need to configure a few things. The following is a step-by-step guide:

1. Download the latest ZIP from the `releases` page.

2. Place the contained `Pandora` folder right in the root directory of your internal storage (e.g. `/storage/emulated/0/Pandora`). **This is important! If there's already another folder named `Pandora` it has to be removed.**

3. Open the `Resources` folder and import the XML file to Tasker. 



## The downloader:

This application essentially allows you to download songs from YouTube. The interesting part is that it's able to fetch data from Spotify and query YouTube accordingly. Using the world's most famous streaming service as backend the downloader is supposed to fulfill two main purposes:

- Fetch similar tracks, albums and artists
- Fetch specified tracks, albums and artists

## The player
The player is supposed to play back your previously downloaded tracks. It only works with VLC Media Player and allows to view artist meta info (provided by Last.fm) and to sort out tracks you don't like. Generally, it offers three modes:
- **Anything**: Allows to play back an arbitrary track.
- **Station**: Allows to play back your downloaded similar tracks.
- **Favorites**: Allows to play back liked tracks.

Similar tracks will be downloaded to so-called "Stations", stored in `Pandora/Stations/<name>`. You'll be able to play those back in VLC and press...

