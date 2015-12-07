package com.gelakinetic.selfie;

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

    AudioCapturer(IAudioReceiver audioReceiver) {
        this.iAudioReceiver = audioReceiver;
    }

    public static AudioCapturer getInstance(IAudioReceiver audioReceiver) {
        if (audioCapturer == null) {
            audioCapturer = new AudioCapturer(audioReceiver);
        }
        return audioCapturer;
    }

    public void start() {

        int samplePerSec = SAMPLES_PER_SECOND;
        int bufferSize = AudioRecord.getMinBufferSize(samplePerSec, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize != AudioRecord.ERROR_BAD_VALUE && bufferSize != AudioRecord.ERROR) {

            audioRecorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, samplePerSec, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize * 10); // bufferSize
            // 10x

            if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecorder.startRecording();
                isRecording = true;
                thread = new Thread(this);
                thread.start();

            }
        }
    }

    public void stop() {
        isRecording = false;
        if (audioRecorder != null) {
            if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                // System.out
                // .println("Stopping the recorder inside AudioRecorder");
                audioRecorder.stop();
            }
            audioRecorder.release();
        }
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (isRecording && audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            short[] tempBuf = new short[SAMPLES_PER_SECOND];
            audioRecorder.read(tempBuf, 0, tempBuf.length);
            iAudioReceiver.capturedAudioReceived(tempBuf);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#finalize()
     */
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        System.out.println("AudioCapturer finalizer");
        if (audioRecorder != null && audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecorder.stop();
            audioRecorder.release();
        }
        audioRecorder = null;
        iAudioReceiver = null;
        thread = null;
    }

}