# Composition Compass

Inspired by the Music Genome Project's `Pandora Radio`, Composition Compass is an Android app that will help you to find new artists you like, or generally other music that may suit your tastes. It uses Pandora's own station engine as a recommendation backend, but contrary to Pandora **it doesn't need a constant internet connection for playback, because everything it finds is downloaded to your device.**

Composition Compass effectively consists of two components:

- **A comprehensive YouTube downloader**, which is capable of downloading entire albums or the top tracks to a given artist just by specifying the artist and / or album name. Besides that, the probably most useful feature is an automatic download of similar tracks, similar artists or similar albums based on Pandora and Last.fm data.

- **An integrated player**, which allows you to play those songs back and "like" the ones you want to hear again, or "dislike" the ones you want to sort out. These songs will then be placed in two different directories, accordingly. If you feel like you discover a true gem, you can also store it in a special folder. This way you'll have an easier time finding it later on.

Of course, you can sort songs out by pressing two buttons on your screen. **But the main advantage of Composition Compass is the possibility of doing so by using your car stereo.** If you regularly drive long distances, you can now discover new songs and artists "on the go" ;)

## Screenshots

[![Screenshot_20220612-102005_Samsung Notes](https://user-images.githubusercontent.com/28263040/173224863-2830cb48-7434-463f-82ed-7558c6267fc6.jpg)](https://user-images.githubusercontent.com/28263040/173224863-2830cb48-7434-463f-82ed-7558c6267fc6.jpg)

## Background

Composition Compass is based on an equally named Tasker project:

<https://github.com/GhostInTheSteiner/Composition-Compass-Legacy>

## Setup

0. Create a Pandora account, unless you already have one. A Last.fm account is optional and only needed if you want to fetch track information from there or display the artist biography in the player.

1. Obtain your Pandora partner credentials. Pandora doesn't publish an official developer API, so the app talks to the same private interface the official apps use. That interface requires a set of "partner" values (partner username and password, device model, an encryption key and a decryption key), which identify the client and are **not** tied to your account. They aren't shipped with this project. Open-source clients like [pydora](https://github.com/mcrute/pydora) and [pianobar](https://6xq.net/pianobar/) document where users source theirs.  
**Note: Your Pandora username and password are separate from these partner values. You need both.**

2. *(Optional)* Obtain a LastFM API key. You can do so by creating a LastFM account and visiting the following link and creating an app: <https://www.last.fm/api/account/create>  
**Note: This key is NOT identical with your LastFM username or password. It's a special key required for access to the API.**

3. Install the `composition-compass.apk` from the releases page. On first launch it'll ask you for a base directory, where it'll store config and all downloads.
  
4. Once started, open the config page (by pressing the "Open config" button) - here you'll you need to insert your Pandora credentials (see [Pandora integration](#pandora-integration) below) and, if you use it, your Last.fm API key.

5. Restart the Composition Compass app. If it doesn't detect the changes to the config, force close the app and restart.

6. You're done!

## Pandora integration

Pandora is the main recommendation source of the downloader. Composition Compass uses Pandora's undocumented JSON-RPC "tuner" API, the same one used by the official apps and reimplemented by [pianobar](https://6xq.net/pianobar/) and [pydora](https://github.com/mcrute/pydora). Pandora doesn't offer "give me similar tracks" as an endpoint, so the app builds one out of Pandora's stations: it seeds a station with what you entered, then reads the configured number of tracks Pandora would play next.

**Note: This is an unofficial integration. Pandora can change or block the interface at any time, and you're responsible for using it in line with Pandora's terms.**

### Configuration under the hood

Usually you don't need to touch this file - the below is explained only for backup and documentation purposes.

Every option is read from `config.ini`, which the app generates and re-reads on every launch:

```
pandoraUsername=you@example.com
pandoraPassword=your-pandora-password
pandoraPartnerUsername=...
pandoraPartnerPassword=...
pandoraDeviceModel=...
pandoraDecryptionKey=...
pandoraEncryptionKey=...
```

`pandoraUsername` and `pandoraPassword` are your normal Pandora login. The other five are the partner values from step 1 of the setup. Make sure the two keys aren't swapped: the encryption key protects what the app sends to Pandora, the decryption key is used for what Pandora sends back.

### How the downloader uses Pandora

1. **Login.** The app first logs in as a partner (unencrypted), then as you. Every request after that is Blowfish-encrypted, exactly like the official clients do it.
2. **Seeding.** The artists, tracks, albums and genres you enter are looked up via Pandora's search and added as seeds to a single temporary station.
3. **Sampling.** The app repeatedly asks the station for its playlist (about four tracks per call, ads are dropped). Each call advances the station, so every call returns new tracks.
4. **Sorting.** What happens with the tracks depends on the mode:
   - `Similar Tracks` takes roughly 40 tracks from the station as they come.
   - `Similar Artists` and `Similar Albums` group the sampled tracks by artist or album, and keep the ones that show up most often.
5. **Downloading.** Every resulting track is searched on YouTube and downloaded, as described in [The Downloader](#the-downloader).

### Limitations

- Pandora's search only knows songs, artists and genre stations, so there is **no real album search**. An album is approximated as the songs found by searching for its title and artist together.
- Sampling is capped at 40 playlist calls (around 160 tracks), regardless of the sample sizes in the config. Those defaults are tuned for services with much higher throughput and would otherwise hit Pandora far harder than is reasonable.
- Genres you enter are used to name folders and don't seed a Pandora genre station on their own.
- Only the API calls go to Pandora. The audio always comes from YouTube.

### Regions, and using Tor

Pandora restricts its service by region, mainly to the US. Requests from other countries are answered with a generic error (code 12 on the partner login). If you're outside the US, Composition Compass can send **only its Pandora API traffic** through an embedded Tor instance whose exit nodes are limited to the US.

- The option is the checkbox **Route Pandora through Tor (US exit)** below the buttons on the main screen. It's on by default and remembered between launches.
- With the checkbox ticked, the app **never contacts Pandora directly**. If Tor can't be started with US-only exits, requests fail with an error instead of quietly going out without protection.
- Downloads from YouTube always connect directly.
- If you're already on a US connection, for example through a VPN, untick the checkbox. Starting Tor takes a moment, and you don't need it.
- US-only exits need GeoIP data. The files `geoip` and `geoip6` have to be present in `app/src/main/assets/` when you build the app.

## The Downloader

The downloader essentially allows you to download songs from YouTube. **The interesting part is that it's able to fetch data from Pandora or Last.fm, and query YouTube accordingly**. Using these services as backend the downloader is supposed to fulfill the following purposes:

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

...and download their most popular tracks to a single Station called `!Artists (<artists>)`. Afterwards, the tracks from `More Interesting` are moved to this Station as well (and prefixed with `!`), so your `More Interesting` folder is empty again.

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
