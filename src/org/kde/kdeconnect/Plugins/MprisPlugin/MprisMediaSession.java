/*
 * Copyright 2017 Matthijs Tijink <matthijstijink@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kde.kdeconnect.Plugins.MprisPlugin;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Pair;

import org.kde.kdeconnect.BackgroundService;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.Helpers.NotificationHelper;
import org.kde.kdeconnect.Plugins.NotificationsPlugin.NotificationReceiver;
import org.kde.kdeconnect_tp.R;

import java.util.HashSet;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * Controls the mpris media control notification
 * <p>
 * There are two parts to this:
 * - The notification (with buttons etc.)
 * - The media session (via MediaSessionCompat; for lock screen control on
 * older Android version. And in the future for lock screen album covers)
 */
public class MprisMediaSession implements SharedPreferences.OnSharedPreferenceChangeListener, NotificationReceiver.NotificationListener {
    private final static int MPRIS_MEDIA_NOTIFICATION_ID = 0x91b70463; // echo MprisNotification | md5sum | head -c 8
    private final static String MPRIS_MEDIA_SESSION_TAG = "org.kde.kdeconnect_tp.media_session";

    private static final MprisMediaSession instance = new MprisMediaSession();

    private boolean spotifyRunning;

    public static MprisMediaSession getInstance() {
        return instance;
    }

    public static MediaSessionCompat getMediaSession() {
        return instance.mediaSession;
    }

    //Holds the device and player displayed in the notification
    private String notificationDevice = null;
    private MprisPlugin.MprisPlayer notificationPlayer = null;
    //Holds the device ids for which we can display a notification
    private final HashSet<String> mprisDevices = new HashSet<>();

    private Context context;
    private MediaSessionCompat mediaSession;

    //Callback for mpris plugin updates
    private final Handler mediaNotificationHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            updateMediaNotification();
        }
    };
    //Callback for control via the media session API
    private final MediaSessionCompat.Callback mediaSessionCallback = new MediaSessionCompat.Callback() {
        @Override
        public void onPlay() {
            notificationPlayer.play();
        }

        @Override
        public void onPause() {
            notificationPlayer.pause();
        }

        @Override
        public void onSkipToNext() {
            notificationPlayer.next();
        }

        @Override
        public void onSkipToPrevious() {
            notificationPlayer.previous();
        }

        @Override
        public void onStop() {
            notificationPlayer.stop();
        }
    };

    /**
     * Called by the mpris plugin when it wants media control notifications for its device
     * <p>
     * Can be called multiple times, once for each device
     *
     * @param _context The context
     * @param mpris    The mpris plugin
     * @param device   The device id
     */
    public void onCreate(Context _context, MprisPlugin mpris, String device) {
        if (mprisDevices.isEmpty()) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(_context);
            prefs.registerOnSharedPreferenceChangeListener(this);
        }
        context = _context;
        mprisDevices.add(device);

        mpris.setPlayerListUpdatedHandler("media_notification", mediaNotificationHandler);
        mpris.setPlayerStatusUpdatedHandler("media_notification", mediaNotificationHandler);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            NotificationReceiver.RunCommand(context, service -> {

                service.addListener(MprisMediaSession.this);

                boolean serviceReady = service.isConnected();

                if (serviceReady) {
                    onListenerConnected(service);
                }
            });
        }

        updateMediaNotification();
    }

    /**
     * Called when a device disconnects/does not want notifications anymore
     * <p>
     * Can be called multiple times, once for each device
     *
     * @param mpris  The mpris plugin
     * @param device The device id
     */
    public void onDestroy(MprisPlugin mpris, String device) {
        mprisDevices.remove(device);
        mpris.removePlayerStatusUpdatedHandler("media_notification");
        mpris.removePlayerListUpdatedHandler("media_notification");
        updateMediaNotification();

        if (mprisDevices.isEmpty()) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    /**
     * Updates which device+player we're going to use in the notification
     * <p>
     * Prefers playing devices/mpris players, but tries to keep displaying the same
     * player and device, while possible.
     *
     * @param service The background service
     */
    private void updateCurrentPlayer(BackgroundService service) {
        Pair<Device, MprisPlugin.MprisPlayer> player = findPlayer(service);

        //Update the last-displayed device and player
        notificationDevice = player.first == null ? null : player.first.getDeviceId();
        notificationPlayer = player.second;
    }

    private Pair<Device, MprisPlugin.MprisPlayer> findPlayer(BackgroundService service) {
        //First try the previously displayed player
        if (notificationDevice != null && mprisDevices.contains(notificationDevice)) {
            Device device = service.getDevice(notificationDevice);

            if (device != null && device.isPluginEnabled("MprisPlugin")) {
                if (shouldShowPlayer(notificationPlayer)){
                    return new Pair<>(device, notificationPlayer);
                }

                // Try a different player for the same device
                MprisPlugin.MprisPlayer player = getPlayerFromDevice(device);
                if (player != null) {
                    return new Pair<>(device, player);
                }
            }
        }

        // Try a different player from another device
        for (Device otherDevice : service.getDevices().values()) {
            MprisPlugin.MprisPlayer player = getPlayerFromDevice(otherDevice);
            if (player != null) {
                return new Pair<>(otherDevice, player);
            }
        }
        return new Pair<>(null, null);
    }

    private MprisPlugin.MprisPlayer getPlayerFromDevice(Device device) {

        if (!mprisDevices.contains(device.getDeviceId()))
            return null;

        MprisPlugin plugin = device.getPlugin(MprisPlugin.class);

        if (plugin == null) {
            return null;
        }

        MprisPlugin.MprisPlayer player = plugin.getPlayingPlayer();
        if (shouldShowPlayer(player)) {
            return player;
        }

        return null;
    }

    private boolean shouldShowPlayer(MprisPlugin.MprisPlayer player) {
        return player != null && !(player.isSpotify() && spotifyRunning);
    }

    /**
     * Update the media control notification
     */
    private void updateMediaNotification() {
        BackgroundService.RunCommand(context, service -> {
            //If the user disabled the media notification, do not show it
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (!prefs.getBoolean(context.getString(R.string.mpris_notification_key), true)) {
                closeMediaNotification();
                return;
            }

            //Make sure our information is up-to-date
            updateCurrentPlayer(service);

            //If the player disappeared (and no other playing one found), just remove the notification
            if (notificationPlayer == null) {
                closeMediaNotification();
                return;
            }

            //Update the metadata and playback status
            if (mediaSession == null) {
                mediaSession = new MediaSessionCompat(context, MPRIS_MEDIA_SESSION_TAG);
                mediaSession.setCallback(mediaSessionCallback);
                mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            }
            MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder();

            //Fallback because older KDE connect versions do not support getTitle()
            if (!notificationPlayer.getTitle().isEmpty()) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE, notificationPlayer.getTitle());
            } else {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE, notificationPlayer.getCurrentSong());
            }
            if (!notificationPlayer.getArtist().isEmpty()) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, notificationPlayer.getArtist());
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, notificationPlayer.getArtist());
            }
            if (!notificationPlayer.getAlbum().isEmpty()) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, notificationPlayer.getAlbum());
            }
            if (notificationPlayer.getLength() > 0) {
                metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, notificationPlayer.getLength());
            }

            Bitmap albumArt = notificationPlayer.getAlbumArt();
            if (albumArt != null) {
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
            }

            mediaSession.setMetadata(metadata.build());
            PlaybackStateCompat.Builder playbackState = new PlaybackStateCompat.Builder();

            if (notificationPlayer.isPlaying()) {
                playbackState.setState(PlaybackStateCompat.STATE_PLAYING, notificationPlayer.getPosition(), 1.0f);
            } else {
                playbackState.setState(PlaybackStateCompat.STATE_PAUSED, notificationPlayer.getPosition(), 0.0f);
            }

            //Create all actions (previous/play/pause/next)
            Intent iPlay = new Intent(service, MprisMediaNotificationReceiver.class);
            iPlay.setAction(MprisMediaNotificationReceiver.ACTION_PLAY);
            iPlay.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
            iPlay.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, notificationPlayer.getPlayer());
            PendingIntent piPlay = PendingIntent.getBroadcast(service, 0, iPlay, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Action.Builder aPlay = new NotificationCompat.Action.Builder(
                    R.drawable.ic_play_white, service.getString(R.string.mpris_play), piPlay);

            Intent iPause = new Intent(service, MprisMediaNotificationReceiver.class);
            iPause.setAction(MprisMediaNotificationReceiver.ACTION_PAUSE);
            iPause.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
            iPause.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, notificationPlayer.getPlayer());
            PendingIntent piPause = PendingIntent.getBroadcast(service, 0, iPause, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Action.Builder aPause = new NotificationCompat.Action.Builder(
                    R.drawable.ic_pause_white, service.getString(R.string.mpris_pause), piPause);

            Intent iPrevious = new Intent(service, MprisMediaNotificationReceiver.class);
            iPrevious.setAction(MprisMediaNotificationReceiver.ACTION_PREVIOUS);
            iPrevious.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
            iPrevious.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, notificationPlayer.getPlayer());
            PendingIntent piPrevious = PendingIntent.getBroadcast(service, 0, iPrevious, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Action.Builder aPrevious = new NotificationCompat.Action.Builder(
                    R.drawable.ic_previous_white, service.getString(R.string.mpris_previous), piPrevious);

            Intent iNext = new Intent(service, MprisMediaNotificationReceiver.class);
            iNext.setAction(MprisMediaNotificationReceiver.ACTION_NEXT);
            iNext.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
            iNext.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, notificationPlayer.getPlayer());
            PendingIntent piNext = PendingIntent.getBroadcast(service, 0, iNext, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Action.Builder aNext = new NotificationCompat.Action.Builder(
                    R.drawable.ic_next_white, service.getString(R.string.mpris_next), piNext);

            Intent iOpenActivity = new Intent(service, MprisActivity.class);
            iOpenActivity.putExtra("deviceId", notificationDevice);
            iOpenActivity.putExtra("player", notificationPlayer.getPlayer());

            /*
                TODO: Remove when Min SDK >= 16
                The only way the intent extra's are delivered on API 14 and 15 is by either using a different requestCode every time
                or using PendingIntent.FLAG_CANCEL_CURRENT instead of PendingIntent.FLAG_UPDATE_CURRENT
             */
            PendingIntent piOpenActivity = TaskStackBuilder.create(context)
                    .addNextIntentWithParentStack(iOpenActivity)
                    .getPendingIntent(Build.VERSION.SDK_INT > 15 ? 0 : (int) System.currentTimeMillis(), PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NotificationHelper.Channels.MEDIA_CONTROL);

            notification
                    .setAutoCancel(false)
                    .setContentIntent(piOpenActivity)
                    .setSmallIcon(R.drawable.ic_play_white)
                    .setShowWhen(false)
                    .setColor(service.getResources().getColor(R.color.primary))
                    .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                    .setSubText(service.getDevice(notificationDevice).getName());

            if (!notificationPlayer.getTitle().isEmpty()) {
                notification.setContentTitle(notificationPlayer.getTitle());
            } else {
                notification.setContentTitle(notificationPlayer.getCurrentSong());
            }
            //Only set the notification body text if we have an author and/or album
            if (!notificationPlayer.getArtist().isEmpty() && !notificationPlayer.getAlbum().isEmpty()) {
                notification.setContentText(notificationPlayer.getArtist() + " - " + notificationPlayer.getAlbum() + " (" + notificationPlayer.getPlayer() + ")");
            } else if (!notificationPlayer.getArtist().isEmpty()) {
                notification.setContentText(notificationPlayer.getArtist() + " (" + notificationPlayer.getPlayer() + ")");
            } else if (!notificationPlayer.getAlbum().isEmpty()) {
                notification.setContentText(notificationPlayer.getAlbum() + " (" + notificationPlayer.getPlayer() + ")");
            } else {
                notification.setContentText(notificationPlayer.getPlayer());
            }

            if (albumArt != null) {
                notification.setLargeIcon(albumArt);
            }

            if (!notificationPlayer.isPlaying()) {
                Intent iCloseNotification = new Intent(service, MprisMediaNotificationReceiver.class);
                iCloseNotification.setAction(MprisMediaNotificationReceiver.ACTION_CLOSE_NOTIFICATION);
                iCloseNotification.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
                iCloseNotification.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, notificationPlayer.getPlayer());
                PendingIntent piCloseNotification = PendingIntent.getBroadcast(service, 0, iCloseNotification, PendingIntent.FLAG_UPDATE_CURRENT);
                notification.setDeleteIntent(piCloseNotification);
            }

            //Add media control actions
            int numActions = 0;
            long playbackActions = 0;
            if (notificationPlayer.isGoPreviousAllowed()) {
                notification.addAction(aPrevious.build());
                playbackActions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
                ++numActions;
            }
            if (notificationPlayer.isPlaying() && notificationPlayer.isPauseAllowed()) {
                notification.addAction(aPause.build());
                playbackActions |= PlaybackStateCompat.ACTION_PAUSE;
                ++numActions;
            }
            if (!notificationPlayer.isPlaying() && notificationPlayer.isPlayAllowed()) {
                notification.addAction(aPlay.build());
                playbackActions |= PlaybackStateCompat.ACTION_PLAY;
                ++numActions;
            }
            if (notificationPlayer.isGoNextAllowed()) {
                notification.addAction(aNext.build());
                playbackActions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
                ++numActions;
            }
            playbackState.setActions(playbackActions);
            mediaSession.setPlaybackState(playbackState.build());

            //Only allow deletion if no music is notificationPlayer
            if (notificationPlayer.isPlaying()) {
                notification.setOngoing(true);
            } else {
                notification.setOngoing(false);
            }

            //Use the MediaStyle notification, so it feels like other media players. That also allows adding actions
            MediaStyle mediaStyle = new MediaStyle();
            if (numActions == 1) {
                mediaStyle.setShowActionsInCompactView(0);
            } else if (numActions == 2) {
                mediaStyle.setShowActionsInCompactView(0, 1);
            } else if (numActions >= 3) {
                mediaStyle.setShowActionsInCompactView(0, 1, 2);
            }
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
            notification.setStyle(mediaStyle);
            notification.setGroup("MprisMediaSession");

            //Display the notification
            mediaSession.setActive(true);
            final NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(MPRIS_MEDIA_NOTIFICATION_ID, notification.build());
        });
    }

    public void closeMediaNotification() {
        //Remove the notification
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(MPRIS_MEDIA_NOTIFICATION_ID);

        //Clear the current player and media session
        notificationPlayer = null;
        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().build());
            mediaSession.setMetadata(new MediaMetadataCompat.Builder().build());
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updateMediaNotification();
    }

    public void playerSelected(MprisPlugin.MprisPlayer player) {
        notificationPlayer = player;
        updateMediaNotification();
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public void onNotificationPosted(StatusBarNotification n) {
        if (n.getPackageName().equals("com.spotify.music")) {
            spotifyRunning = true;
            updateMediaNotification();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public void onNotificationRemoved(StatusBarNotification n) {
        if (n.getPackageName().equals("com.spotify.music")) {
            spotifyRunning = false;
            updateMediaNotification();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public void onListenerConnected(NotificationReceiver service) {
        for (StatusBarNotification n : service.getActiveNotifications()) {
            if (n.getPackageName().equals("com.spotify.music")) {
                spotifyRunning = true;
                updateMediaNotification();
            }
        }
    }
}
