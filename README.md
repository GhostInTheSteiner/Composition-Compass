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

2. Place the contained `Pandora` folder in a `Music` directory on your internal storage (e.g. `/storage/emulated/0/Music/Pandora`).  
**This is important! If there's already another folder named `Pandora` it has to be removed.**

3. Open Tasker and import the XML inside the `Pandora/!resources` folder.

4. Once imported, launch the `CarPlayer.OpenItem` Task. It'll prompt you for all required dependencies. If you're missing one of them Composition Compass will automatically install it and ask you to restart the Task.

5. After you're done you should see the main menu. Tap on the `Open Downloader` button. The downloader app will open and ask you to configure missing fields in the configuration.  
**Note: You need to have at least one text editor installed on your system! I recommend `Acode` or `Markor` for a nicely rendered view of the config file.**

6. To obtain your Spotify API credentials visit the following link and create an app:  
https://developer.spotify.com/dashboard/  
Your app will then contain your Client ID and Secret.  
**Note: Those values are NOT identical with your Spotify username and password. They're special keys required for access to the API.**

7. To obtain your LastFM API key visit the following link:  
https://www.last.fm/api/account/create  
**Note: This key is NOT identical with your LastFM username or password. It's a special key required for access to the API.**

8. Go back to Composition Compass' config file and paste your API credentials at the corresponding fields. Remember to save the file!

9. You're done!


## The downloader:

The downloader essentially allows you to download songs from YouTube. **The interesting part is that it's able to fetch data from Spotify or Last.fm, and query YouTube accordingly**. Using the world's most famous streaming services as backend the downloader is supposed to fulfill two main purposes:

- Fetch similar tracks, albums and artists  
- Fetch specified tracks, albums and artists  

Similar tracks will be downloaded to so-called "Stations", stored in `Pandora/Stations/<name>`.

Specified tracks will be downloaded to...

`Pandora/Artists/<name>` if only an artist has been provided.  
`Pandora/Artists/<name>` if an artist and a track has been provided (same as above).  
`Pandora/Albums/<name>` if an artist and an album has been provided.  


## The player

The player is supposed to play back your previously downloaded tracks. It displays the biography of the currently played back song's artist, and the typical genres said artist is affiliated with. However, **the real benefit of using the integrated player is to separate tracks you like from tracks you don't like.**


### Playback of Stations

First, tap on the `Open Player` button, then on the `Browse` button to select one of the folders you downloaded tracks to before.

Playback of Stations works by pressing the `Volume Up` and `Volume Down` keys to move them to...

`Pandora/!automated/Favorites` and  
`Pandora/!automated/Recycle Bin`

...respectively. If you keep pressing `Volume Up` for a second or more, the current track will be moved straight to...

`Pandora/!automated/Favorites/More Interesting`  

..., skipping the `Favorites` folder. If you encounter a track you like *especially*, you can give it a special place right away. This way you'll have an easier time finding it later on ;)

The current track will also be skipped at the same time, so you don't have to listen to it until the very end. Of course, you can also press the `Like` and `Dislike` buttons displayed on-screen. The volume button triggers can be enabled and disabled by checking the checkbox below the player.

The triggers are mainly implemented to sort out tracks without looking on the screen, which is useful while driving long distances in your car (assuming you have an automatic gearbox, otherwise you should probably reconsider that use-case lol)


#### IMPORTANT

**Keep in mind activating the triggers will cause your volume level to be kept at a value of `4`, and pressing the volume buttons will always reset the level!** This has been implemented to prevent your Android device from muting the stream (and to keep you from messing around with your phone while driving ;) )

Also, due to limitations with Android's wake lock, **you must keep the screen turned on when using the volume button triggers!**. Don't worry about your screen timeout, Composition Compass will prevent your screen from automatically turning off for as long as the player is open.

One last important piece of information: As long as the triggers are active, **leaving the player and turning off the screen will mute the volume to prevent accidental playback**. If you keep the triggers disabled the volume level won't be affected.


### Playback of Favorites
 
Playback of Favorites is especially useful if you have a lot of tracks in your `Favorites` folder and want to sort out the ones you don't like. It effectively works like the Playback of Stations described above, except for the fact that you need to select the `Favorites` folder. Then...

`Pandora/!automated/Favorites/More Interesting` is used to "like" a track, and  
`Pandora/!automated/Favorites/Less Interesting` is used to "dislike" a track.  
