/**
 * Copyright 2015 Adam Feinstein
 * <p/>
 * This file is part of Selfr.
 * <p/>
 * Selfr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p/>
 * Selfr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with Selfr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.gelakinetic.selfr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class HeadsetStateReceiver extends BroadcastReceiver {
    private final CameraActivity mCameraActivity;

    /**
     * Default constructor, clears a lint warning
     */
    public HeadsetStateReceiver() {
        super();
        mCameraActivity = null;
    }

    /**
     * Constructor, this should be used
     *
     * @param cameraActivity The CameraActivity to notify of intents
     */
    public HeadsetStateReceiver(CameraActivity cameraActivity) {
        mCameraActivity = cameraActivity;
    }

    /**
     * Receives intents from the system, either plugging in or removing a selfie stick.
     *
     * @param context The Context in which the receiver is running.
     * @param intent  The Intent being received.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        int state = intent.getExtras().getInt("state");
        int microphone = intent.getExtras().getInt("microphone");
        if (state == 1 && microphone == 1) {
            mCameraActivity.setSelfieStickConnected(true);
        } else {
            mCameraActivity.setSelfieStickConnected(false);
        }
    }
}
