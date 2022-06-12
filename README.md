# Composition Compass


![Screenshot_20220612-102005_Samsung Notes](https://user-images.githubusercontent.com/28263040/173224317-7e94ba6a-1550-47d0-89e6-a6164e6cfacc.jpg)


Inspired by the Music Genome Project's `Pandora Radio`, Composition Compass is an Android app that will help you to find new artists you like, or generally other music that may suit your tastes. Contrary to Pandora though, **this application doesn't require a constant internet connection and is completely free.**

Composition Compass effectively consists of two components:
 
- **A comprehensive YouTube downloader**, which is capable of downloading entire albums or the top tracks to a given artist just by specifying the artist and / or album name. Besides that, the probably most useful feature is an automatic download of similar tracks, similar artists or similar albums based on Spotify and Last.fm data.

- **An integrated player**, which allows you to play those songs back and "like" the ones you want to hear again, or "dislike" the ones you want to sort out. These songs will then be placed in two different directories, accordingly. If you feel like you discover a true gem, you can also store it in a special folder. This way you'll have an easier time finding it later on.

Of course, you can sort songs out by pressing two buttons on your screen. **But the main advantage of Composition Compass is the possibility of doing so by using your car stereo.** If you regularly drive long distances, you can now discover new songs and artists "on the go" ;)

## Terms of use

By using this project or its source code, for any purpose and in any shape or form, you grant your **implicit agreement** to all the following statements:

- You **condemn Russia and its military aggression against Ukraine**
- You **recognize that Russia is an occupant that unlawfully invaded a sovereign state**
- You **support Ukraine's territorial integrity**
- You **reject false narratives perpetuated by Russian state propaganda**

To learn more about the war and how you can help, [click here](https://www.comebackalive.in.ua).


## Background

Composition Compass is based on an equally named Tasker project:

https://github.com/GhostInTheSteiner/Composition-Compass-Legacy


## Setup

**Note: You need to have at least one text editor installed on your system! I recommend `Acode` or `Markor` for a nicely rendered view of the config file.**

0. Create a Spotify and a Last.fm account, unless you already have one. At least one of both is required if you want to fetch track information from there.

1. Obtain your Spotify API credentials. You can do so by visiting the following link and creating an app:  
https://developer.spotify.com/dashboard/  
Your app will then contain your Client ID and Secret.  
**Note: Those values are NOT identical with your Spotify username and password. They're special keys required for access to the API.**

2. Obtain your LastFM API key. You can do so by visiting the following link and creating an app:
https://www.last.fm/api/account/create  
**Note: This key is NOT identical with your LastFM username or password. It's a special key required for access to the API.**

3. Install the `composition-compass.apk` from the releases page. On first launch it'll open the config file, where you need to insert your Spotify and Last.fm API credentials. **Remember to save the file!**

4. Restart the Composition Compass app. If it doesn't detect the changes to the config, force close the app and restart.

4. You're done!


## The Downloader

The downloader essentially allows you to download songs from YouTube. **The interesting part is that it's able to fetch data from Spotify or Last.fm, and query YouTube accordingly**. Using the world's most famous streaming services as backend the downloader is supposed to fulfill two main purposes:

- Fetch similar tracks, albums and artists  
- Fetch specified tracks, albums and artists  
- Fetch artists to tracks "liked" in the player (`Liked Artists`)  


### Similar Tracks

Similar tracks will be downloaded to so-called "Stations", stored in `Pandora/Stations/<name>`.

Each download mode (`Similar Tracks`, `Similar Artists` and `Similar Albums`) will download tracks to a dedicated subfolder, which name is based on the fields you filled in.

For example, `Similar Tracks` are downloaded to...

`Pandora/Stations/<artist_name> (<track_name>, <genre_name>)`

Sometimes there's an exclamation mark (`!`) in front of the Station name. It exists to differentiate between the `Similar Tracks` Station (no exclamation mark) and other, more complex stations like `Similar Artists`, `Similar Albums` and `Liked Artists` (all of which use an exclamation mark).


### Specified Tracks

Specified tracks will be downloaded to...

`Pandora/Artists/<name>` if only an artist has been provided. This directory will contain the most popular tracks of the given artist.  

`Pandora/Artists/<name>` if an artist and a track has been provided (same as above). This directory will only contain the given track.  

`Pandora/Albums/<name>` if an artist and an album has been provided. This directory will contain all tracks of the given album.  


### Fetch artists to tracks "liked" in the player (`Liked Artists`)

`Liked Artists` will retrieve the artists from the tracks currently present in...

`Pandora/!automated/Favorites/More Interesting`  

...and download their most popular tracks to a single Station called `!Artists (<artists>)`.

I implemented this download mode mostly for convenience, after I realized all I did after "liking" tracks in the player was essentially to download their artists' top tracks and create a Station of those "by hand".

With the `Liked Artists` mode this is no longer necessary. It's an easy way to "hear more" of what you previously liked, so you should be quickly able to tell whether or not "that one cool band" was just a One-Hit wonder or if you've actually found your next favorite musician.


## The Player

The player is supposed to play back your previously downloaded tracks. It displays the biography of the currently played back song's artist, and the typical genres said artist is affiliated with. However, **the real benefit of using the integrated player is to separate tracks you like from tracks you don't like.**


### Playback of Stations

#### Without a car stereo / via AUX

First, tap on the `Open Player` button, then on the `Browse` button to select one of the folders you downloaded tracks to before.

Playback of Stations works by pressing the `Volume Up` and `Volume Down` keys to move them to...

`Pandora/!automated/Favorites` and  
`Pandora/!automated/Recycle Bin`

...respectively. If you keep pressing `Volume Up` for a second or more, the current track will be moved straight to...

`Pandora/!automated/Favorites/More Interesting`  

..., skipping the `Favorites` folder. If you encounter a track you like *especially*, you can give it a special place right away. This way you'll have an easier time finding it later on ;)

Of course, you can also press the `Like` and `Dislike` buttons displayed on-screen. The volume button triggers can be enabled and disabled by checking the checkbox below the player.

**Note:** Upon liking or disliking a track it won't be skipped immediately, which allows you to hear all of if. **You can still initiate a skip to the next track by pressing the like or dislike button once again.**


#### With a car stereo

The triggers were mainly implemented to sort out tracks without looking on the screen, which is useful while driving long distances in your car. However – while the volume button triggers work with any car stereo – they're far from ideal. You'll still have to keep you smartphone around you, and make sure it doesn't drop to the floor while pushing the breaks.

Hence, **it's also possible (and very recommendable!) to use the buttons on your car stereo directly.** As long your phone is paired with the stereo via a Bluetooth connection (AVRCP), the following will work:

`Skip Forward`: Like a track (to `Favorites`  
`Play / Pause`: Like a track (to `Favorites/More Interesting`)  
`Skip Backwards`: Dislike a track (to `Recycle Bin`)  

Please note this only works while the volume button triggers are enabled. Disabling them will cause your car stereo's controls to work as expected.


#### IMPORTANT

**Keep in mind activating the triggers will cause your volume level to be kept at a value of `4`, and pressing the volume buttons will always reset the level!** This has been implemented to prevent your Android device from muting the stream (and to keep you from messing around with your phone while driving ;) )

Also, due to limitations with Android's wake lock, **you must keep the screen turned on when using the volume button triggers!** Don't worry about your screen timeout, Composition Compass will prevent your screen from automatically turning off for as long as the player is open.

One last important piece of information: As long as the triggers are active, **leaving the player will mute the volume to prevent accidental playback**. If you keep the triggers disabled the volume level won't be affected.


### Playback of Favorites
 
Playback of Favorites is especially useful if you have a lot of tracks in your `Favorites` folder and want to sort out the ones you don't like. It effectively works like the Playback of Stations described above, except for the fact that you need to select the `Favorites` folder. Then...

`Pandora/!automated/Favorites/More Interesting` is used to "like" a track, and  
`Pandora/!automated/Favorites/Less Interesting` is used to "dislike" a track.  

