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

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class CameraActivity extends AppCompatActivity implements IAudioReceiver {

    /* Enums */
    public enum ViewState {
        VISIBLE,
        IN_TRANSITION,
        GONE
    }

    /* Constants */
    private static final int UI_ANIMATION_DELAY = 200;
    private static final int PERMISSION_REQUEST_CODE = 162;

    /* UI Objects */
    private FrameLayout mContentView;
    private FrameLayout mFlashView;
    private TextView mNoStickWarningView;
    private View mControlsView;
    private CameraPreview mCameraPreview;

    /* State objects */
    private ViewState mSystemBarVisible;
    private ViewState mControlsVisible;
    private int mCameraType = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private String mFlashMode = Camera.Parameters.FLASH_MODE_OFF;
    private boolean mHardwareFlashSupported = false;
    private boolean mDebounce = false;
    private int mDeviceRotation = 0;
    private float mOldBrightness;

    /* Hardware interface objects */
    private HeadsetStateReceiver mHeadsetStateReceiver;
    private AudioCapturer mAudioCapturer;
    private Camera mCamera;
    private OrientationEventListener mOrientationEventListener;

    /* Handler and Runnables */
    private Handler mHandler;
    private final Runnable mHideAllRunnable = new Runnable() {
        /**
         * Calls hide(), can be posted delayed with a handler
         */
        @Override
        public void run() {
            hideControls();
        }
    };

    private final Runnable mHideSystemBarRunnable = new Runnable() {
        /**
         * Delayed removal of status and navigation bar
         * Note that some of these constants are new as of API 16 (Jelly Bean)
         * and API 19 (KitKat). It is safe to use them, as they are inlined
         * at compile-time and do nothing on earlier devices.
         */
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            mSystemBarVisible = ViewState.GONE;
        }
    };

    private final Runnable mShowControlsRunnable = new Runnable() {
        /**
         * Makes the controls visible again, and animates their entrance
         */
        @Override
        public void run() {
            /* Show the controls view */
            mControlsView.setVisibility(View.VISIBLE);
            /* Animate it's entrance */
            Animation flyInAnimation = AnimationUtils.loadAnimation(getApplicationContext(),
                    R.anim.animation_fly_in);
            flyInAnimation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                    /* Unused */
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    /* Mark the controls as visible after the animation finishes */
                    mControlsVisible = ViewState.VISIBLE;
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                    /* Unused */
                }
            });
            mControlsView.startAnimation(flyInAnimation);
        }
    };

    private final Runnable mSetFrontFlashRunnable = new Runnable() {
        /**
         * Shows a maximum brightness, white "flash" view, waits two seconds
         * for the screen and camera to adjust, and takes a picture
         */
        @Override
        public void run() {
            /* Save the old brightness, set current to max */
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            mOldBrightness = layout.screenBrightness;
            layout.screenBrightness = 1F;
            getWindow().setAttributes(layout);
            /* Show the "flash" screen */
            mFlashView.setVisibility(View.VISIBLE);
            /* Take a picture, after letting the "flash" settle */
            mHandler.postDelayed(mTakePictureRunnable, 2000);
        }
    };

    private final Runnable mClearFrontFlashRunnable = new Runnable() {
        /**
         * Restores the brightness and hides the white "flash" view
         */
        @Override
        public void run() {
            /* Restore the brightness */
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            layout.screenBrightness = mOldBrightness;
            getWindow().setAttributes(layout);

            /* Hide the "flash" view */
            mFlashView.setVisibility(View.GONE);
        }
    };

    private final Runnable mTakePictureRunnable = new Runnable() {
        /**
         * Take a picture and set a timer to not allow another picture
         * for three seconds
         */
        @Override
        public void run() {
            try {
                /* Just to be sure */
                if (mCamera == null) {
                    return;
                }
                mCamera.takePicture(null, null, mPicture);
                mDebounce = true;
                mHandler.postDelayed(mClearDebounceRunnable, 3000);
            } catch (RuntimeException e) {
                /* That didn't work... */
            }
        }
    };

    private final Runnable mClearDebounceRunnable = new Runnable() {
        /**
         * Clear the debounce timer, three seconds after a picture is taken
         */
        @Override
        public void run() {
            mDebounce = false;
        }
    };

    private final Runnable mSetFrontShutterRunnable = new Runnable() {
        /**
         * Blackout the screen, like a shutter snap
         */
        @Override
        public void run() {
            mFlashView.setBackgroundColor(getResources().getColor(android.R.color.black));
            mFlashView.setVisibility(View.VISIBLE);
        }
    };

    private final Runnable mClearFrontShutterRunnable = new Runnable() {
        /**
         * Clear the black, shutter view from the screen
         */
        @Override
        public void run() {
            mFlashView.setVisibility(View.GONE);
            mFlashView.setBackgroundColor(getResources().getColor(android.R.color.white));
        }
    };

    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {

        /**
         * Callback for after a picture was taken
         * @param data      The bytes to be saved as an image
         * @param camera    The Camera object that took the picture
         */
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            /* If there is no hardware flash and the front facing camera was used */
            if (!mHardwareFlashSupported &&
                    mCameraType == Camera.CameraInfo.CAMERA_FACING_FRONT &&
                    mFlashMode.equals(Camera.Parameters.FLASH_MODE_ON)) {
                /* Clear the "flash" screen */
                runOnUiThread(mClearFrontFlashRunnable);
            } else {
                /* Otherwise, make a shutter effect */
                runOnUiThread(mSetFrontShutterRunnable);
                mHandler.postDelayed(mClearFrontShutterRunnable, 500);
            }

            /* Get a file to write the picture to */
            File pictureFile = getOutputImageFile();
            if (pictureFile == null) {
                return;
            }

            try {
                /* Save the image */
                FileOutputStream fos = new FileOutputStream(pictureFile);
                fos.write(data);
                fos.close();

                /* Notify the media scanner so it displays in the gallery */
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(pictureFile)));
            } catch (IOException e) {
                /* Eat it */
            }
        }
    };

    /**
     * A safe way to get an instance of the Camera object. Also sets up picture size & focus type
     *
     * @param cameraType Camera.CameraInfo.CAMERA_FACING_FRONT or
     *                   Camera.CameraInfo.CAMERA_FACING_BACK
     * @return A Camera object if it was created, or null
     */
    @Nullable
    public static Camera getCameraInstance(int cameraType) {
        Camera camera = null;
        try {
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            /* Scan through all the cameras for one of the specified type */
            for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); camIdx++) {
                Camera.getCameraInfo(camIdx, cameraInfo);
                if (cameraInfo.facing == cameraType) {
                    try {
                        /* Open the camera, get default parameters */
                        camera = Camera.open(camIdx);
                        Camera.Parameters parameters = camera.getParameters();

                        /* Set the image to native resolution */
                        List<Camera.Size> sizes = parameters.getSupportedPictureSizes();
                        Camera.Size nativeSize = null;
                        int maxHeight = Integer.MIN_VALUE;
                        for (Camera.Size size : sizes) {
                            if (size.height > maxHeight) {
                                maxHeight = size.height;
                                nativeSize = size;
                            }
                        }
                        if (nativeSize != null) {
                            parameters.setPictureSize(nativeSize.width, nativeSize.height);
                        }

                        /* Set autofocus, if we can */
                        List<String> focusModes = parameters.getSupportedFocusModes();
                        if (focusModes != null &&
                                focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        }

                        /* Set the parameters */
                        camera.setParameters(parameters);

                    } catch (RuntimeException e) {
                        /* Eat it */
                    }
                }
            }
        } catch (Exception e) {
            /* Camera is not available (in use or does not exist) */
        }
        return camera; /* returns null if camera is unavailable */
    }

    /**
     * Perform initialization of all non-camera views
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut
     *                           down then this Bundle contains the data it most recently supplied
     *                           in onSaveInstanceState. Note: Otherwise it is null.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);

        mControlsView = findViewById(R.id.fullscreen_content_controls);
        mContentView = (FrameLayout) findViewById(R.id.fullscreen_content);
        mFlashView = (FrameLayout) findViewById(R.id.flash_view);
        mNoStickWarningView = (TextView) findViewById(R.id.no_stick_text);

        mControlsVisible = ViewState.VISIBLE;
        mSystemBarVisible = ViewState.VISIBLE;

        mHandler = new Handler();

        /* Set up the user interaction to manually show or hide the system UI. */
        mContentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleControls();
            }
        });

        /* Set up the Toolbar */
        Toolbar mToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
        mToolbar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO fill this with useful information
                AlertDialog.Builder builder = new AlertDialog.Builder(CameraActivity.this);
                String title;
                try {
                    title = getString(R.string.app_name) + " " +
                            getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (PackageManager.NameNotFoundException e) {
                    title = getString(R.string.app_name);
                }
                builder.setMessage(R.string.about_message).setTitle(title);
                builder.create().show();
            }
        });
    }

    /**
     * When the activity resumes, request permissions, or if the app has them initialize:
     * - audio capturer (to detect button presses)
     * - camera (to take pictures)
     * - headset state receiver (to detect the selfie stick)
     * - accelerometer (to properly set picture rotation)
     */
    @Override
    protected void onResume() {
        super.onResume();

        /* Check to see what permissions we're granted */
        ArrayList<String> requestedPermissions = new ArrayList<>(3);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestedPermissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestedPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestedPermissions.add(Manifest.permission.RECORD_AUDIO);
        }

        /* If we don't have all of the permissions */
        if (requestedPermissions.size() > 0) {
            String permissionStrings[] = new String[requestedPermissions.size()];
            requestedPermissions.toArray(permissionStrings);
            /* Request the permissions */
            ActivityCompat.requestPermissions(this,
                    permissionStrings,
                    PERMISSION_REQUEST_CODE);
        } else {
            /* Otherwise, fire everything up */
            /* Set up the audio capture */
            mAudioCapturer = AudioCapturer.getInstance(this);

            /* Set up the camera */
            mCamera = getCameraInstance(mCameraType);
            if (mCamera != null) {
                mCameraPreview = new CameraPreview(this, mCamera);
                mContentView.addView(mCameraPreview);
            }

            /* Register the headset state receiver */
            mHeadsetStateReceiver = new HeadsetStateReceiver(this);
            registerReceiver(mHeadsetStateReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));

            /* Set up the accelerometer */
            mOrientationEventListener = new OrientationEventListener(CameraActivity.this,
                    SensorManager.SENSOR_DELAY_NORMAL) {

                /**
                 * Called when the orientation of the device has changed. orientation parameter is
                 * in degrees, ranging from 0 to 359. orientation is 0 degrees when the device is
                 * oriented in its natural position, 90 degrees when its left side is at the top,
                 * 180 degrees when it is upside down, and 270 degrees when its right side is to the
                 * top. ORIENTATION_UNKNOWN is returned when the device is close to flat and the
                 * orientation cannot be determined.
                 *
                 * @param orientation The orientation of the device. (0->359, ORIENTATION_UNKNOWN)
                 */
                @Override
                public void onOrientationChanged(int orientation) {
                    /* If the orientation is unknown, don't bother */
                    if (orientation == ORIENTATION_UNKNOWN) {
                        return;
                    }

                    /* Clamp rotation to nearest 90 degree wedge, depending on camera */
                    Camera.CameraInfo info = new Camera.CameraInfo();
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        if (315 <= orientation || orientation < 45) {
                            mDeviceRotation = 270;
                        } else if (45 <= orientation && orientation < 135) {
                            mDeviceRotation = 180;
                        } else if (135 <= orientation && orientation < 225) {
                            mDeviceRotation = 90;
                        } else if (225 <= orientation && orientation < 315) {
                            mDeviceRotation = 0;
                        }
                    } else {
                        if (315 <= orientation || orientation < 45) {
                            mDeviceRotation = 90;
                        } else if (45 <= orientation && orientation < 135) {
                            mDeviceRotation = 180;
                        } else if (135 <= orientation && orientation < 225) {
                            mDeviceRotation = 270;
                        } else if (225 <= orientation && orientation < 315) {
                            mDeviceRotation = 0;
                        }
                    }
                }
            };
            if (mOrientationEventListener.canDetectOrientation()) {
                mOrientationEventListener.enable();
            }

            /* Trigger the initial hide() shortly after the activity has been
             * created, to briefly hint to the user that UI controls
             * are available.
             */
            delayedHide(2000);
        }
    }

    /**
     * Clean up and release all hardware resources when the activity pauses
     */
    @Override
    protected void onPause() {
        super.onPause();

        /* Clean up the camera & preview */
        if (mCameraPreview != null) {
            mContentView.removeView(mCameraPreview);
            mCameraPreview = null;
        }
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

        /* Clean up the audio */
        if (mAudioCapturer != null) {
            mAudioCapturer.stop();
            mAudioCapturer = null;
        }

        /* Clean up the receiver */
        if (mHeadsetStateReceiver != null) {
            unregisterReceiver(mHeadsetStateReceiver);
            mHeadsetStateReceiver = null;
        }

        /* Clean up the accelerometer */
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
            mOrientationEventListener = null;
        }
    }

    /**
     * Callback for the result from requesting permissions. This method is invoked for every call
     * on requestPermissions(String[], int). If permissions aren't granted, pop a toast & finish()
     *
     * @param requestCode  The request code passed in requestPermissions(String[], int).
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions which is either
     *                     android.content.pm.PackageManager.PERMISSION_GRANTED or
     *                     android.content.pm.PackageManager.PERMISSION_DENIED. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        boolean allPermissionsGranted = true;
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < grantResults.length; i++) {
                switch (permissions[i]) {
                    case Manifest.permission.CAMERA:
                    case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                    case Manifest.permission.RECORD_AUDIO: {
                        if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                            allPermissionsGranted = false;
                        }
                        break;
                    }
                    default: {
                        /* Some unknown permission */
                    }
                }
            }
        }
        if (!allPermissionsGranted) {
            Toast.makeText(this, getString(R.string.permission_failure),
                    Toast.LENGTH_LONG).show();
            this.finish();
        }
    }

    /**
     * Toggle the visible state of the controls
     */
    private void toggleControls() {
        /* If there is no animation in progress */
        if (mControlsVisible != ViewState.IN_TRANSITION
                && mSystemBarVisible != ViewState.IN_TRANSITION) {
            /* If the controls are visible */
            if (mControlsVisible == ViewState.VISIBLE
                    && mSystemBarVisible == ViewState.VISIBLE) {
                /* Hide them */
                hideControls();
            } else if (mControlsVisible == ViewState.GONE
                    && mSystemBarVisible == ViewState.GONE) {
                /* Otherwise, show them */
                showControls();
            }
        }
    }

    /**
     * Hide the system bar & toolbar
     */
    private void hideControls() {
        /* Mark the views as animating */
        mControlsVisible = ViewState.IN_TRANSITION;
        mSystemBarVisible = ViewState.IN_TRANSITION;

        /* Hide UI first */
        Animation flyInAnimation = AnimationUtils.loadAnimation(this,
                R.anim.animation_fly_out);
        flyInAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                /* Unused */
            }

            /**
             * When the animation completes, hide the controls view
             * @param animation The animation which reached its end.
             */
            @Override
            public void onAnimationEnd(Animation animation) {
                mControlsView.setVisibility(View.GONE);
                mControlsVisible = ViewState.GONE;
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
                /* Unused */
            }
        });
        mControlsView.startAnimation(flyInAnimation);

        /* Schedule a runnable to remove the status and navigation bar after a delay */
        mHandler.removeCallbacks(mShowControlsRunnable);
        mHandler.postDelayed(mHideSystemBarRunnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in [delay] milliseconds, canceling any
     * previously scheduled calls.
     *
     * @param delayMillis How long to delay before hiding the UI
     */
    private void delayedHide(int delayMillis) {
        mHandler.removeCallbacks(mHideAllRunnable);
        mHandler.postDelayed(mHideAllRunnable, delayMillis);
    }

    /**
     * Show the system bar & toolbar
     */
    @SuppressLint("InlinedApi")
    private void showControls() {
        /* Mark the views as animating */
        mSystemBarVisible = ViewState.IN_TRANSITION;
        mControlsVisible = ViewState.IN_TRANSITION;

        /* Show the system bar */
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mSystemBarVisible = ViewState.VISIBLE;

        /* Schedule a runnable to display UI elements after a delay */
        mHandler.removeCallbacks(mHideSystemBarRunnable);
        mHandler.postDelayed(mShowControlsRunnable, UI_ANIMATION_DELAY);

        /* Hide the UI in 5 seconds, should be enough for a button press */
        delayedHide(5000);
    }

    /**
     * Initialize the contents of the Activity's standard options menu. The menu is inflated from
     * R.menu.camera_menu
     *
     * @param menu The options menu in which you place your items.
     * @return true, since the menu should be displayed
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.camera_menu, menu);
        return true;
    }

    /**
     * This hook is called whenever an item in your options menu is selected.
     *
     * @param item The menu item that was selected.
     * @return false to allow normal menu processing to proceed, true to consume it here.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.photo_library:
                /* Launch an intent to open a gallery app */
                Intent i = new Intent(Intent.ACTION_VIEW,
                        android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                startActivity(i);
                return true;
            case R.id.camera_switch: {
                /* Switch the camera between front and rear */
                switchCamera(item);
                return true;
            }
            case R.id.flash_setting: {
                /* Switch the flash between on and off */
                switchFlash(item);
                return true;
            }
            default: {
                /* Pass the event along */
                return super.onOptionsItemSelected(item);
            }
        }
    }

    /**
     * If the flash is on, turn it off, and vice versa. Front facing cameras without hardware
     * flash will briefly display a max brighess, pure white screen
     *
     * @param item The menu item to change the icon in order to reflect the current state
     */
    private void switchFlash(MenuItem item) {
        /* Change the flash mode & icon */
        switch (mFlashMode) {
            case Camera.Parameters.FLASH_MODE_OFF: {
                mFlashMode = Camera.Parameters.FLASH_MODE_ON;
                item.setIcon(R.drawable.ic_flash_on_white_24dp);
                break;
            }
            case Camera.Parameters.FLASH_MODE_ON: {
                mFlashMode = Camera.Parameters.FLASH_MODE_OFF;
                item.setIcon(R.drawable.ic_flash_off_white_24dp);
                break;
            }
        }
        /* Then set the parameter */
        setFlashParameter();

        /* Hide the UI */
        delayedHide(2500);
    }

    /**
     * If the rear camera is being used, switch to the front camera, and vice versa
     * This blocks the UI thread, which is generally bad, but nothing is happening anyway,
     * and swapping camera resources on a separate thread is a recipe for crashes
     *
     * @param item The menu item to change the icon in order to reflect the current state
     */
    private void switchCamera(MenuItem item) {
        /* Switch from one camera type to the other, adjust the icon as necessary */
        switch (mCameraType) {
            case Camera.CameraInfo.CAMERA_FACING_FRONT: {
                mCameraType = Camera.CameraInfo.CAMERA_FACING_BACK;
                item.setIcon(R.drawable.ic_camera_rear_white_24dp);
                break;
            }
            case Camera.CameraInfo.CAMERA_FACING_BACK: {
                mCameraType = Camera.CameraInfo.CAMERA_FACING_FRONT;
                item.setIcon(R.drawable.ic_camera_front_white_24dp);
                break;
            }
        }

        /* Remove old camera & preview */
        if (mCameraPreview != null) {
            mContentView.removeView(mCameraPreview);
        }

        /* Release current camera resources */
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

        /* Get a new camera instance */
        mCamera = getCameraInstance(mCameraType);

        /* Set the preview with the new Camera object */
        if (mCamera != null) {
            mCameraPreview = new CameraPreview(getApplicationContext(), mCamera);
            mContentView.addView(mCameraPreview);

            /* Make sure the flash parameter is correct */
            setFlashParameter();
        }

        /* Hide the UI */
        delayedHide(2500);
    }

    /**
     * Sets the current flash mode to the current Camera object
     */
    private void setFlashParameter() {
        if (mCamera == null) {
            return;
        }
        /* If the camera supports flash, set the parameter */
        Camera.Parameters parameters = mCamera.getParameters();
        List<String> flashModes = parameters.getSupportedFlashModes();
        if (flashModes != null &&
                flashModes.contains(Camera.Parameters.FLASH_MODE_OFF) &&
                flashModes.contains(Camera.Parameters.FLASH_MODE_ON)) {
            switch (mFlashMode) {
                case Camera.Parameters.FLASH_MODE_OFF: {
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    break;
                }
                case Camera.Parameters.FLASH_MODE_ON: {
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
                    break;
                }
            }
            mCamera.setParameters(parameters);
            mHardwareFlashSupported = true;
        } else {
            mHardwareFlashSupported = false;
        }
    }

    /**
     * Create a File for saving an image
     */
    @Nullable
    private File getOutputImageFile() {
        /* Make sure the external storage is mounted first */
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return null;
        }

        /* Make a Selfr folder in the DCIM directory */
        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), getString(R.string.app_name));

        /* Create the storage directory if it does not exist */
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                /* Can't make the folder */
                return null;
            }
        }

        /* Create a media file name */
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.getDefault()).format(new Date());
        return new File(mediaStorageDir.getPath() + File.separator + "IMG_" + timeStamp + ".jpg");
    }

    /**
     * Called from AudioCapturer when a buffer of audio was received
     *
     * @param tempBuf A buffer of audio received
     */
    @Override
    public void capturedAudioReceived(short[] tempBuf) {

        /* If the app just took a picture, and is debouncing, don't look for button presses */
        if (mDebounce) {
            return;
        }
        /* If there is a spike in the buffer */
        if (findMax(tempBuf) > 32000) {
            /* And the camera isn't null */
            if (mCamera != null) {
                /* Set rotation */
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setRotation(mDeviceRotation);
                mCamera.setParameters(parameters);

                if (!mHardwareFlashSupported &&
                        mCameraType == Camera.CameraInfo.CAMERA_FACING_FRONT &&
                        mFlashMode.equals(Camera.Parameters.FLASH_MODE_ON)) {
                    /* No hardware flash & front camera, draw the screen bright white */
                    runOnUiThread(mSetFrontFlashRunnable);
                } else {
                    /* Take a picture immediately */
                    mTakePictureRunnable.run();
                }
            }
        }
    }

    /**
     * Helper function to find the largest element in an array
     *
     * @param array An array to search
     * @return The largest element in the given array
     */
    short findMax(short[] array) {
        short max = Short.MIN_VALUE;
        for (short element : array) {
            if (element > max) {
                max = element;
            }
        }
        return max;
    }

    /**
     * Called from HeadsetStateReceiver when a selfie stick is attached or removed.
     * This is called when the activity is created too.
     *
     * @param selfieStickConnected true if a stick was connected, false if it was removed
     */
    public void setSelfieStickConnected(boolean selfieStickConnected) {
        if (selfieStickConnected) {
            mNoStickWarningView.setVisibility(View.GONE);
            mAudioCapturer.start();
        } else {
            mNoStickWarningView.setVisibility(View.VISIBLE);
            mAudioCapturer.stop();
        }
    }
}