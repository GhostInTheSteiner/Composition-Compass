# Composition Compass

Composition Compass is a set of Android apps that allow you to download similar songs to a given input sample. You can then play those songs back and sort them out without even looking on your screen, which is especially useful while driving long distances in your car.

Composition Compass is based on an equally named Tasker-only project:

https://github.com/GhostInTheSteiner/Composition-Compass-Legacy

Contrary to the original Composition Compass its successor consists of two separate applications: A downloader and a player. The downloader has been written in Kotlin and is provided as simple APK file. The player is still a Tasker project and therefore provided as XML.

Note #1: To use the player you need to purchase [Tasker for Android](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm).

Note #2: You **no longer need root** to use Composition Compass ;)


## Setup

The setup process has been greatly simplified, but you'll still need to configure a few things. The following is a step-by-step guide:

1. Download the latest ZIP from the `releases` page.

2. Place the contained `Pandora` folder right in the root directory of your internal storage (e.g. `/storage/emulated/0/Pandora`). **This is important! If there's already another folder named `Pandora` it has to be removed.**

3. Open Tasker and import the XML inside the `Pandora/!resources` folder.

4. Once imported, launch the `CarPlayer.OpenItem` Task. It'll prompt you for all required dependencies. If you're missing one of them Composition Compass will automatically install it and ask you to restart the Task.

5. After you're done you should see the main menu. Tap on the `Open Downloader` button. The downloader app will open and ask you to configure missing fields in the configuration. **Note: You need to have at least one text editor installed on your system! I recommend `Acode` or `Markor` for a nicely rendered view of the config file.**

6. To obtain your Spotify API credentials visit the following link and create an app:  
https://developer.spotify.com/dashboard/  
Your app will then contain your Client ID and Secret.  
**Note: Those values are NOT identical with your Spotify username and password. They're special keys required for access to the API.**

7. To obtain your LastFM API key visit the following link:  
https://www.last.fm/api/account/create  
**Note: This key is NOT identical with your LastFM username or password. It's a special key required for access to the API.**

8. Go back to Composition Compass' config file and paste your API credentials at the corresponding fields. Remember to save the file!

9. You're done!


## Usage

Open VLC Media Player. After a few seconds an icon resembling a radio station will appear on the right. Initially, you'll always be in `Anything` mode, so you're able to play back an arbitrary track and check artist info. If you want to switch to the other modes tap on `Open Menu`.


## The downloader:

This application essentially allows you to download songs from YouTube. The interesting part is that it's able to fetch data from Spotify and query YouTube accordingly. Using the world's most famous streaming service as backend the downloader is supposed to fulfill two main purposes:

- Fetch similar tracks, albums and artists
- Fetch specified tracks, albums and artists

## The player

The player is supposed to play back your previously downloaded tracks. It only works with VLC Media Player and allows to view artist meta info (provided by Last.fm) and to sort out tracks you don't like. Generally, it offers three modes:

- **Anything**: Allows to play back an arbitrary track.
- **Station**: Allows to play back your downloaded similar tracks.
- **Favorites**: Allows to play back liked tracks.

Similar tracks will be downloaded to so-called "Stations", stored in `Pandora/Stations/<name>`. Using the `Stations` mode you'll be able to play those back in VLC and press Volume Up and Down keys to move them to `Pandora/!automated/Favorites` and `Pandora/!automated/Recycle Bin` respectively. The current track will also be skipped in VLC at the same time, so you don't have to listen to it until the very end.

The `Favorites` mode is essentially the same, however it plays back the `Pandora/!automated/Favorites` folder instead. This is especially useful if you have a lot of tracks in that folder and want to sort out the ones you don't like.

