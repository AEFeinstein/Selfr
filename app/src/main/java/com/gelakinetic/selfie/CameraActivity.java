package com.gelakinetic.selfie;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
@SuppressWarnings("deprecation")
public class CameraActivity extends AppCompatActivity {

    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;
    private static final String TAG = "tag";
    private final Handler mHideHandler = new Handler();
    private FrameLayout mContentView;
    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    private View mControlsView;
    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            mControlsView.setVisibility(View.VISIBLE);
        }
    };
    private boolean mVisible;
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    private HeadsetStateReceiver mHeadsetStateReceiver;
    private AudioCapturer mAudioCapturer;
    private FileWriter mFileWriter = null;
    private Camera mCamera;
    private Handler mHandler;
    private boolean mDebounce = false;
    private CameraPreview mCameraPreview;
    private int mDeviceRotation = 0;
    private OrientationEventListener mOrientationEventListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);

        mVisible = true;
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        mContentView = (FrameLayout) findViewById(R.id.fullscreen_content);

        mHandler = new Handler();

        // Set up the user interaction to manually show or hide the system UI.
        mContentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });

        Toolbar mToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
    }

    short findMax(short[] array) {
        short max = Short.MIN_VALUE;
        for (short element : array) {
            if (element > max) {
                max = element;
            }
        }
        return max;
    }

    @Override
    protected void onResume() {
        super.onResume();

        /* Set up the audio capture */
        mAudioCapturer = AudioCapturer.getInstance(new IAudioReceiver() {
            @Override
            public void capturedAudioReceived(short[] tempBuf) {

                if (mDebounce) {
                    return;
                }
                if (findMax(tempBuf) > 32000) {
                    /* Set rotation */
                    Camera.Parameters parameters = mCamera.getParameters();
                    parameters.setRotation(mDeviceRotation);
                    mCamera.setParameters(parameters);

                    mCamera.takePicture(null, null, mPicture);
                    mDebounce = true;
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mDebounce = false;
                        }
                    }, 3000);
                }
            }
        });

        /* Set up the camera */
        mCamera = getCameraInstance(Camera.CameraInfo.CAMERA_FACING_FRONT);
        mCameraPreview = new CameraPreview(this, mCamera);
        mContentView.addView(mCameraPreview);

        /* Register the headset state receiver */
        mHeadsetStateReceiver = new HeadsetStateReceiver();
        registerReceiver(mHeadsetStateReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));

        /* Set up the accelerometer */
        mOrientationEventListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                /* If the orientation is unknown, don't bother */
                if (orientation == ORIENTATION_UNKNOWN) {
                    return;
                }

                /* Clamp rotation to nearest 90 degree wedge */
                int rotation = 0;
                if (315 <= orientation || orientation < 45) {
                    rotation = 270;
                } else if (45 <= orientation && orientation < 135) {
                    rotation = 180;
                } else if (135 <= orientation && orientation < 225) {
                    rotation = 90;
                } else if (225 <= orientation && orientation < 315) {
                    rotation = 0;
                }

                /* Take into account which way the camera is pointing */
                android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    rotation = (360 - rotation);
                }
                mDeviceRotation = rotation;
            }
        };
        if (mOrientationEventListener.canDetectOrientation()) {
            mOrientationEventListener.enable();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        /* Clean up the camera */
        mContentView.removeView(mCameraPreview);
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;

        /* Clean up the audio */
        mAudioCapturer.stop();
        mAudioCapturer = null;

        /* Clean up the receiver */
        unregisterReceiver(mHeadsetStateReceiver);
        mHeadsetStateReceiver = null;

        /* Clean up the accelerometer */
        mOrientationEventListener.disable();
        mOrientationEventListener = null;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    private void toggle() {
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        mControlsView.setVisibility(View.GONE);
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in [delay] milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }

    private class HeadsetStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getExtras().getInt("state");
            int microphone = intent.getExtras().getInt("microphone");
            if (state == 1 && microphone == 1) {
                mAudioCapturer.start();
            } else {
                mAudioCapturer.stop();
                try {
                    mFileWriter.close();
                } catch (IOException | NullPointerException e) {
                    /* eat it */
                }
                mFileWriter = null;
            }
        }

    }

    /**
     * A safe way to get an instance of the Camera object.
     */
    public static Camera getCameraInstance(int cameraType) {
        Camera c = null;
        try {
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); camIdx++) {
                Camera.getCameraInfo(camIdx, cameraInfo);
                if (cameraInfo.facing == cameraType) {
                    try {
                        /* Open the camera, get default parameters */
                        c = Camera.open(camIdx);
                        Camera.Parameters parameters = c.getParameters();

                        /* Set the image to native resolution */
                        List<Camera.Size> sizes = parameters.getSupportedPictureSizes();
                        Camera.Size nativeSize = sizes.get(0); // TODO search for largest?
                        parameters.setPictureSize(nativeSize.width, nativeSize.height);

                        /* Set the parameters */
                        c.setParameters(parameters);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Camera failed to open: " + e.getLocalizedMessage());
                    }
                }
            }
        } catch (Exception e) {
            // Camera is not available (in use or does not exist)
        }
        return c; // returns null if camera is unavailable
    }

    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {

            File pictureFile = getOutputMediaFile(MEDIA_TYPE_IMAGE);
            if (pictureFile == null) {
                Log.d(TAG, "Error creating media file, check storage permissions: ");
                return;
            }

            try {
                FileOutputStream fos = new FileOutputStream(pictureFile);
                fos.write(data);
                fos.close();
                Toast.makeText(CameraActivity.this, pictureFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            } catch (FileNotFoundException e) {
                Log.d(TAG, "File not found: " + e.getMessage());
            } catch (IOException e) {
                Log.d(TAG, "Error accessing file: " + e.getMessage());
            }

            /* Restart the preview */
            mCamera.startPreview();
        }
    };

    public static final int MEDIA_TYPE_IMAGE = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;

    /**
     * Create a File for saving an image or video
     */
    private static File getOutputMediaFile(int type) {
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "MyCameraApp");
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.d("MyCameraApp", "failed to create directory");
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "IMG_" + timeStamp + ".jpg");
        } else if (type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "VID_" + timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }
}