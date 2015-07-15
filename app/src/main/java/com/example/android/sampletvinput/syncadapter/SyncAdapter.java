/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sampletvinput.syncadapter;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.database.Cursor;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;

import com.example.android.sampletvinput.TvContractUtils;
import com.example.android.sampletvinput.data.Program;
import com.example.android.sampletvinput.player.TvInputPlayer;
import com.felkertech.n.cumulustv.ChannelDatabase;
import com.felkertech.n.cumulustv.JSONChannel;
import com.felkertech.n.cumulustv.TvManager;

import org.json.JSONException;

import java.nio.channels.Channel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A SyncAdapter implementation which updates program info periodically.
 */
public class SyncAdapter extends AbstractThreadedSyncAdapter {
    public static final String TAG = "SyncAdapter";

    public static final String BUNDLE_KEY_INPUT_ID = "bundle_key_input_id";
    public static final long SYNC_FREQUENCY_SEC = 60 * 60 * 6;  // 6 hours
    private static final int SYNC_WINDOW_SEC = 60 * 60 * 12;  // 12 hours
    private static final int BATCH_OPERATION_COUNT = 100;

    private final Context mContext;

    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        mContext = context;
    }

    public SyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        mContext = context;
    }

    /**
     * Called periodically by the system in every {@code SYNC_FREQUENCY_SEC}.
     */
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        Log.d(TAG, "onPerformSync(" + account + ", " + authority + ", " + extras + ")");
        String inputId = extras.getString(SyncAdapter.BUNDLE_KEY_INPUT_ID);
        if (inputId == null) {
            return;
        }
        //REFRESH CHANNEL DATA FROM SHAREDPREFERENCES
        List<TvManager.ChannelInfo> list = null;
        try {
            ChannelDatabase cdn = new ChannelDatabase(getContext());
            Log.d(TAG, cdn.toString());
            list = cdn.getChannels();
            Log.d(TAG, list.toString());
            Log.d(TAG, "Now updating channels");
            if(list.size() <= 0)
                return; //You haven't added any channels!
            TvContractUtils.updateChannels(getContext(), inputId, list);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        List<TvManager.ChannelInfo> channels = getChannels(mContext);
        LongSparseArray<TvManager.ChannelInfo> channelMap = TvContractUtils.buildChannelMap(
                mContext.getContentResolver(), inputId, channels);
        for (int i = 0; i < channelMap.size(); ++i) {
            Uri channelUri = TvContract.buildChannelUri(channelMap.keyAt(i));
            insertPrograms(channelUri, channelMap.valueAt(i));
//            insertProgram(channelUri, channelMap.valueAt(i));
        }
        Log.d(TAG, "Sync performed");
    }

    public static List<TvManager.ChannelInfo> getChannels(Context mContext) {
        TvContentRating rating = TvContentRating.createRating(
                "com.android.tv",
                "US_TV",
                "US_TV_PG",
                "US_TV_D", "US_TV_L");
        String ABCNews = "http://abclive.abcnews.com/i/abc_live4@136330/index_1200_av-b.m3u8"; //FIXME


        String channels = TvContract.buildInputId(new ComponentName("com.felkertech.n.cumulustv", ".SampleTvInput"));
        Uri channelsQuery = TvContract.buildChannelsUriForInput(channels);
        Log.d(TAG, channels+" "+channelsQuery.toString());
        List<TvManager.ChannelInfo> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(channelsQuery, null, null, null, null);
            while(cursor != null && cursor.moveToNext()) {
                TvManager.ChannelInfo channel = new TvManager.ChannelInfo();
                channel.number = cursor.getString(cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER));
                channel.name = cursor.getString(cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME));
                channel.originalNetworkId = cursor.getInt(cursor.getColumnIndex(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID));
                channel.transportStreamId = cursor.getInt(cursor.getColumnIndex(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID));
                channel.serviceId = cursor.getInt(cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID));
                channel.videoHeight = 1080;/* FIXME */
                channel.videoWidth = 1920;/* FIXME */
            /*TvManager.ChannelInfo channel = new TvManager.ChannelInfo(
                    cursor.getString(cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)),
                    cursor.getString(cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)),
                    "", *//* Will get logo a little later *//*
                    cursor.getInt(cursor.getColumnIndex(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID)),
                    cursor.getInt(cursor.getColumnIndex(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID)),
                    cursor.getInt(cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID)),
                    1920, *//*
                    1080, *//* FIXME *//*
                    null);*/
//            Long rowId = mExistingChannelsMap.get(channel.originalNetworkId);
                ChannelDatabase cdn = new ChannelDatabase(mContext);
                JSONChannel jsonChannel = cdn.findChannel(channel.number);
                channel.logoUrl = jsonChannel.getLogo();

                List<TvManager.ProgramInfo> infoList = new ArrayList<>();
                infoList.add(new TvManager.ProgramInfo(channel.name + " Live", jsonChannel.getLogo(),
                        "Currently streaming", 60 * 60, new TvContentRating[]{rating}, new String[]{TvContract.Programs.Genres.NEWS}, jsonChannel.getUrl(), TvInputPlayer.SOURCE_TYPE_HTTP_PROGRESSIVE, 0));

                channel.programs = infoList;
                Log.d(TAG, channel.name+" ["+jsonChannel.getName()+"] ready "+jsonChannel.getLogo()+" "+jsonChannel.getUrl());
                list.add(channel);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        //tODO send blobs into a database

        return list;
    }

    /**
     * Inserts programs from now to {@link SyncAdapter#SYNC_WINDOW_SEC}.
     *
     * @param channelUri The channel where the program info will be added.
     * @param channelInfo {@link TvManager.ChannelInfo} instance which includes program information.
     */
    private void insertPrograms(Uri channelUri, TvManager.ChannelInfo channelInfo) {
        long durationSumSec = 0;
        List<ContentValues> programs = new ArrayList<>();
        if(channelInfo.programs == null) {
            Log.d(TAG, "Channelinfo.programs == null for "+channelUri.toString());
            return;
        }
        for (TvManager.ProgramInfo program : channelInfo.programs) {
            Log.d(TAG, "Finding "+program.title+ " "+program.durationSec+" "+program.description+" "+program.posterArtUri+" "+program.videoUrl);
            durationSumSec += program.durationSec;

            ContentValues values = new ContentValues();
            values.put(TvContract.Programs.COLUMN_CHANNEL_ID, ContentUris.parseId(channelUri));
            values.put(TvContract.Programs.COLUMN_TITLE, program.title);
            values.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, program.description);
            values.put(TvContract.Programs.COLUMN_CONTENT_RATING,
                    TvContractUtils.contentRatingsToString(program.contentRatings));
            values.put(TvContract.Programs.COLUMN_CANONICAL_GENRE,
                    TvContract.Programs.Genres.encode(program.genres));
            if (!TextUtils.isEmpty(program.posterArtUri)) {
                values.put(TvContract.Programs.COLUMN_POSTER_ART_URI, program.posterArtUri);
            }
            // NOTE: {@code COLUMN_INTERNAL_PROVIDER_DATA} is a private field where TvInputService
            // can store anything it wants. Here, we store video type and video URL so that
            // TvInputService can play the video later with this field.
            values.put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA,
                    TvContractUtils.convertVideoInfoToInternalProviderData(program.videoType,
                            program.videoUrl));
            programs.add(values);
        }

//        long nowSec = System.currentTimeMillis() / 1000;
        long nowSec = new Date().getTime()/1000;
        long insertionEndSec = nowSec + SYNC_WINDOW_SEC;
        long lastProgramEndTimeSec = TvContractUtils.getLastProgramEndTimeMillis(
                mContext.getContentResolver(), channelUri) / 1000;
        if (nowSec < lastProgramEndTimeSec) {
            nowSec = lastProgramEndTimeSec;
        }
        long insertionStartTimeSec = nowSec - nowSec % (durationSumSec+1);
        long nextPos = insertionStartTimeSec;
        Log.d(TAG, nowSec+" "+durationSumSec+" "+(nowSec % (durationSumSec+1))+" "+insertionStartTimeSec+" "+nextPos+" "+insertionEndSec);
        for (int i = 0; nextPos < insertionEndSec; ++i) {
            long programStartSec = nextPos;
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            int programsCount = channelInfo.programs.size();
            for (int j = 0; j < programsCount; ++j) {
                TvManager.ProgramInfo program = channelInfo.programs.get(j);
                Log.d(TAG, "BulkAdding "+program.title+" @ "+(programStartSec/1000/60/60));
                ops.add(ContentProviderOperation.newDelete(
                    TvContract.Programs.CONTENT_URI).build());
                Log.d(TAG, "Doop");
                ops.add(ContentProviderOperation.newInsert(
                        TvContract.Programs.CONTENT_URI)
                        .withValues(programs.get(j))
                        .withValue(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                                programStartSec * 1000)
                        .withValue(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
                                (programStartSec + program.durationSec) * 1000)
                        .build());
                programStartSec = programStartSec + program.durationSec;

                // Throttle the batch operation not to face TransactionTooLargeException.
                if (j % BATCH_OPERATION_COUNT == BATCH_OPERATION_COUNT - 1
                        || j == programsCount - 1) {
                    try {
                        mContext.getContentResolver().applyBatch(TvContract.AUTHORITY, ops);
                    } catch (RemoteException | OperationApplicationException e) {
                        Log.e(TAG, "Failed to insert programs.", e);
                        return;
                    }
                    ops.clear();
                }
            }
            nextPos = insertionStartTimeSec + i * durationSumSec;
        }
    }
}