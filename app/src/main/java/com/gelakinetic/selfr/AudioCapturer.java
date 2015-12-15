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

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

public class AudioCapturer implements Runnable {

    private static final int SAMPLES_PER_SECOND = 16000;
    private static AudioCapturer audioCapturer;
    private AudioRecord audioRecorder = null;
    private Thread thread = null;
    private boolean isRecording;
    private IAudioReceiver iAudioReceiver;

    /**
     * Default constructor
     *
     * @param audioReceiver An iAudioReceiver which will receive samples
     */
    AudioCapturer(IAudioReceiver audioReceiver) {
        this.iAudioReceiver = audioReceiver;
    }

    /**
     * Use this instead of the constructor. This manages a static instance of AudioCapturer
     *
     * @param audioReceiver An iAudioReceiver which will receive samples
     * @return The AudioCapturer to use
     */
    public static AudioCapturer getInstance(IAudioReceiver audioReceiver) {
        if (audioCapturer == null) {
            audioCapturer = new AudioCapturer(audioReceiver);
        }
        return audioCapturer;
    }

    /**
     * Start recording audio and passing samples to the given iAudioReceiver
     *
     * @return true if the recording started, false otherwise
     */
    public boolean start() {

        /* Figure out how big the buffer needs to be */
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLES_PER_SECOND,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        /* If it's all good */
        if (bufferSize != AudioRecord.ERROR_BAD_VALUE && bufferSize != AudioRecord.ERROR) {

            /* Make an AudioRecord object */
            audioRecorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLES_PER_SECOND,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 10);
            /* If it's all good */
            if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                /* Start running this on a background thread */
                audioRecorder.startRecording();
                isRecording = true;
                thread = new Thread(this);
                thread.start();
                return true;
            }
        }
        return false;
    }

    /**
     * Stop recording audio and release resources
     */
    public void stop() {
        isRecording = false;
        if (audioRecorder != null) {
            if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                /* Stop recording if it was recording */
                audioRecorder.stop();
            }
            /* And release if it wasn't null */
            audioRecorder.release();
        }
    }

    /**
     * Called when this starts running on a background thread. This does the actual audio capture.
     */
    @Override
    public void run() {
        /* Set the thread priority */
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (isRecording &&
                audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            /* Read a buffer of samples and pass them to the iAudioReceiver */
            short[] tempBuf = new short[SAMPLES_PER_SECOND];
            audioRecorder.read(tempBuf, 0, tempBuf.length);
            iAudioReceiver.capturedAudioReceived(tempBuf);
        }
    }

    /**
     * Called by the garbage collector on an object when garbage collection determines that there
     * are no more references to the object.
     *
     * @throws Throwable If anything goes wrong
     */
    @Override
    protected void finalize() throws Throwable {
        super.finalize();

        /* Release any resources, just in case */
        if (audioRecorder != null && audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecorder.stop();
            audioRecorder.release();
        }
        audioRecorder = null;
        iAudioReceiver = null;
        thread = null;
    }
}