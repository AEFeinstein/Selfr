package com.gelakinetic.selfie;

/**
 * Created by Adam on 11/24/2015.
 */
public interface IAudioReceiver {
    void capturedAudioReceived(short[] tempBuf);
}
