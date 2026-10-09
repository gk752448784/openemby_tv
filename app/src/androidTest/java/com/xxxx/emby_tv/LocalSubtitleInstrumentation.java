package com.xxxx.emby_tv;

import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import com.xxxx.emby_tv.ui.player.SubtitleConfigBuilder;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;

/** Independent device check of the production local-file datasource and subtitle formats.
 * Fixtures are supplied in target cacheDir by the validation script, without any server account.
 */
public class LocalSubtitleInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        ExoPlayer[] player = new ExoPlayer[1];
        try {
            for (String codec : new String[]{"srt", "ass"}) {
                CountDownLatch cue = new CountDownLatch(1);
                AtomicReference<String> failure = new AtomicReference<>();
                runOnMainSync(() -> {
                    File directory = new File(getTargetContext().getCacheDir(), "subtitle-check");
                    MediaItem.SubtitleConfiguration config = new MediaItem.SubtitleConfiguration.Builder(
                        Uri.fromFile(new File(directory, "test." + codec)))
                        .setId("-2").setLabel("ASSRT: test [-2]")
                        .setMimeType(SubtitleConfigBuilder.INSTANCE.getMimeType(codec))
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
                    player[0] = new ExoPlayer.Builder(getTargetContext())
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(getTargetContext())
                            .setDataSourceFactory(new DefaultDataSource.Factory(getTargetContext(),
                                new OkHttpDataSource.Factory(new OkHttpClient())))).build();
                    player[0].addListener(new Player.Listener() {
                        @Override public void onCues(CueGroup group) {
                            for (androidx.media3.common.text.Cue item : group.cues) {
                                if (item.text != null && item.text.toString().contains("LOCAL SUBTITLE CHECK")) cue.countDown();
                            }
                        }
                        @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                            failure.set(error.getErrorCodeName()); cue.countDown();
                        }
                    });
                    player[0].setMediaItem(new MediaItem.Builder().setUri(Uri.fromFile(new File(directory, "test.mp4")))
                        .setSubtitleConfigurations(java.util.Collections.singletonList(config)).build(), 1000);
                    player[0].prepare(); player[0].play();
                });
                if (!cue.await(20, TimeUnit.SECONDS) || failure.get() != null)
                    throw new IllegalStateException(codec + " cue failed: " + failure.get());
                runOnMainSync(() -> {
                    player[0].pause();
                    if (player[0].getPlayWhenReady()) throw new IllegalStateException("pause failed");
                    player[0].setTrackSelectionParameters(player[0].getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                    if (!player[0].getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
                        throw new IllegalStateException("subtitle disable failed");
                    player[0].setTrackSelectionParameters(player[0].getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
                    player[0].seekTo(2000); player[0].play(); player[0].release(); player[0] = null;
                });
            }
            result.putString("result", "PASS: local SRT and ASS cues, pause, disable, re-enable, seek");
            finish(-1, result);
        } catch (Throwable error) {
            runOnMainSync(() -> { if (player[0] != null) player[0].release(); });
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(0, result);
        }
    }
}
