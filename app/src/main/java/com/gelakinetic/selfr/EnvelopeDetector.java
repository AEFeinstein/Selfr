package com.gelakinetic.selfr;

public class EnvelopeDetector {

    /* Filter coefficient */
    private static final float ALPHA = 0.5f;

    /* Save the last output sample for the next calculations, to keep calculations continuous */
    private float mLastOutput = 0;

    /**
     * Approximate the envelope of an imput signal by squaring it and running it through
     * a low pass filter
     *
     * @param samples Input, samples from the AudioCapturer
     * @param outputs Output, the approximate envelope of the input samples
     */
    void findEnvelope(short[] samples, float[] outputs) throws IllegalArgumentException {
        /* Counter variable */
        int i;

        /* Seed outputs with the last output value from prior calculations */
        outputs[0] = mLastOutput;

        /* For each sample */
        for(i = 0; i < samples.length; i++) {
            /* Square it, multiply by ALPHA, and sum it with the prior output
             * multiplied by (1 - ALPHA)
             */
            outputs[i + 1] = (ALPHA * (samples[i] * samples[i])) + ((1 - ALPHA) * outputs[i]);
        }

        /* Save the last output sample for the next processing call */
        mLastOutput = outputs[i];
    }

    /**
     * Helper function to calculate the average of an array
     *
     * @param array An array to average
     * @return The average value of elements in the array
     */
    static float average(float[] array) {
        float avg = 0;
        for (float f : array) {
            avg += f;
        }
        return (avg / array.length);
    }
}












