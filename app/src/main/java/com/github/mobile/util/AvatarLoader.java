/*
 * Copyright 2012 GitHub Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.mobile.util;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import com.actionbarsherlock.app.ActionBar;
import com.github.mobile.R;
import com.google.inject.Inject;
import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.picasso.OkHttpDownloader;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Transformation;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.egit.github.core.CommitUser;
import org.eclipse.egit.github.core.Contributor;
import org.eclipse.egit.github.core.User;

import roboguice.util.RoboAsyncTask;

/**
 * Avatar utilities
 */
public class AvatarLoader {
    static final int DISK_CACHE_SIZE = 50 * 1024 * 1024; // 50MB

    private static final String TAG = "AvatarLoader";

    private static final float CORNER_RADIUS_IN_DIP = 3;

    private final Context context;
    private final Picasso p;

    private final float cornerRadius;

    private final RoundedCornersTransformation transformation = new RoundedCornersTransformation();

    /**
     * The max size of avatar images, used to rescale images to save memory.
     */
    private static int avatarSize = 0;

    /**
     * Create avatar helper
     *
     * @param context The context from which you're calling the method.
     */
    @Inject
    public AvatarLoader(final Context context) {
        this.context = context;

        OkHttpClient client = new OkHttpClient();

        // Install an HTTP cache in the application cache directory.
        File cacheDir = new File(context.getCacheDir(), "http");
        Cache cache = new Cache(cacheDir, DISK_CACHE_SIZE);
        client.setCache(cache);

        p = new Picasso.Builder(context).downloader(new OkHttpDownloader(client)).build();

        float density = context.getResources().getDisplayMetrics().density;
        cornerRadius = CORNER_RADIUS_IN_DIP * density;

        if (avatarSize == 0) {
            avatarSize = getMaxAvatarSize(context);
        }

        // TODO remove this eventually
        // Delete the old cache
        final File avatarDir = new File(context.getCacheDir(), "avatars/github.com");
        if (avatarDir.isDirectory())
            deleteCache(avatarDir);
    }

    /**
     * Sets the logo on the {@link ActionBar} to the user's avatar.
     *
     * @param actionBar An ActionBar object on which you're placing the user's avatar.
     * @param user      An AtomicReference that points to the desired user.
     * @return this helper
     */
    public void bind(final ActionBar actionBar, final User user) {
        bind(actionBar, new AtomicReference<User>(user));
    }

    /**
     * Sets the logo on the {@link ActionBar} to the user's avatar.
     *
     * @param actionBar     An ActionBar object on which you're placing the user's avatar.
     * @param userReference An AtomicReference that points to the desired user.
     * @return this helper
     */
    public void bind(final ActionBar actionBar, final AtomicReference<User> userReference) {
        if (userReference == null)
            return;

        final User user = userReference.get();
        if (user == null)
            return;

        String avatarUrl = user.getAvatarUrl();
        if (TextUtils.isEmpty(avatarUrl))
            return;

        // Remove the URL params as they are not needed and break cache
        if (avatarUrl.contains("?") && !avatarUrl.contains("gravatar")) {
            avatarUrl = avatarUrl.substring(0, avatarUrl.indexOf("?"));
        }

        final String url = avatarUrl;

        new FetchAvatarTask(context) {

            @Override
            public BitmapDrawable call() throws Exception {
                Bitmap image = Bitmap.createScaledBitmap(p.load(url).get(), avatarSize, avatarSize, false);
                return new BitmapDrawable(context.getResources(), ImageUtils.roundCorners(image, cornerRadius));
            }

            @Override
            protected void onSuccess(BitmapDrawable image) throws Exception {
                actionBar.setLogo(image);
            }
        }.execute();
    }

    /**
     * Bind view to image at URL
     *
     * @param view The ImageView that is to display the user's avatar.
     * @param user A User object that points to the desired user.
     */
    public void bind(final ImageView view, final User user) {
        bind(view, getAvatarUrl(user));
    }

    /**
     * Bind view to image at URL
     *
     * @param view The ImageView that is to display the user's avatar.
     * @param user A CommitUser object that points to the desired user.
     */
    public void bind(final ImageView view, final CommitUser user) {
        bind(view, getAvatarUrl(user));
    }

    /**
     * Bind view to image at URL
     *
     * @param view        The ImageView that is to display the user's avatar.
     * @param contributor A Contributor object that points to the desired user.
     */
    public void bind(final ImageView view, final Contributor contributor) {
        bind(view, contributor.getAvatarUrl());
    }

    private void bind(final ImageView view, String url) {
        if (url == null) {
            p.load(R.drawable.spinner_inner).resize(avatarSize, avatarSize).into(view);
            return;
        }

        if (url.contains("?") && !url.contains("gravatar")) {
            url = url.substring(0, url.indexOf("?"));
        }

        p.load(url)
                .placeholder(R.drawable.gravatar_icon)
                .resize(avatarSize, avatarSize)
                .transform(transformation)
                .into(view);
    }

    private String getAvatarUrl(User user) {
        String avatarUrl = user.getAvatarUrl();
        if (TextUtils.isEmpty(avatarUrl)) {
            avatarUrl = getAvatarUrl(GravatarUtils.getHash(user.getEmail()));
        }
        return avatarUrl;
    }

    private String getAvatarUrl(CommitUser user) {
        return getAvatarUrl(GravatarUtils.getHash(user.getEmail()));
    }

    private String getAvatarUrl(String id) {
        if (!TextUtils.isEmpty(id))
            return "http://gravatar.com/avatar/" + id + "?d=404";
        else
            return null;
    }

    private int getMaxAvatarSize(final Context context) {
        int[] attrs = { android.R.attr.layout_height };
        TypedArray array = context.getTheme().obtainStyledAttributes(R.style.AvatarXLarge, attrs);
        // Passing default value of 100px, but it shouldn't resolve to default anyway.
        int size = array.getLayoutDimension(0, 100);
        array.recycle();
        return size;
    }

    private boolean deleteCache(final File cache) {
        if (cache.isDirectory())
            for (File f : cache.listFiles())
                deleteCache(f);
        return cache.delete();
    }

    private static abstract class FetchAvatarTask extends RoboAsyncTask<BitmapDrawable> {

        private static final Executor EXECUTOR = Executors.newFixedThreadPool(1);

        private FetchAvatarTask(Context context) {
            super(context, EXECUTOR);
        }

        @Override
        protected void onException(Exception e) throws RuntimeException {
            Log.d(TAG, "Avatar load failed", e);
        }
    }

    public class RoundedCornersTransformation implements Transformation {
        @Override
        public Bitmap transform(Bitmap source) {
            return ImageUtils.roundCorners(source, cornerRadius);
        }

        @Override public String key() {
            return "RoundedCornersTransformation";
        }
    }
}
