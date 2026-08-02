/*
 * Copyright (C) 2026 Global Connect.
 * Licensed under the GNU General Public License version 2.
 */
package com.github.olga_yakovleva.rhvoice.android;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class OfflineTtsInstrumentedTest {
    private static final String TAG = "RHVoiceDeviceTest";
    private static final String ENGINE_PACKAGE = "com.github.olga_yakovleva.rhvoice.android";

    @Test
    public void testOfflineEngineVoicesLocalesRateAndAudio() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch initialized = new CountDownLatch(1);
        AtomicInteger initializationStatus = new AtomicInteger(TextToSpeech.ERROR);
        TextToSpeech tts = new TextToSpeech(
            context,
            status -> {
                initializationStatus.set(status);
                initialized.countDown();
            },
            ENGINE_PACKAGE
        );

        try {
            assertTrue("TTS initialization timed out", initialized.await(60, TimeUnit.SECONDS));
            assertEquals(TextToSpeech.SUCCESS, initializationStatus.get());
            assertTrue(
                tts.getEngines().stream().anyMatch(engine -> ENGINE_PACKAGE.equals(engine.name))
            );
            File dataRoot = new File(context.getFilesDir(), "rhvoice-data");
            assertFalse(new File(dataRoot, "bundle-1.17.2").exists());
            assertFalse(
                new File(dataRoot, "bundle-1.18.4-gc1.staging-interrupted").exists()
            );

            Map<String, Voice> voices = new HashMap<>();
            for (Voice voice : tts.getVoices()) {
                voices.put(voice.getName(), voice);
            }
            assertEquals(new HashSet<String>() {{
                add("Slt");
                add("Mateo");
            }}, voices.keySet());
            assertEquals("en", voices.get("Slt").getLocale().getLanguage());
            assertEquals("US", voices.get("Slt").getLocale().getCountry());
            assertEquals("es", voices.get("Mateo").getLocale().getLanguage());
            assertEquals("MX", voices.get("Mateo").getLocale().getCountry());
            assertFalse(voices.get("Slt").isNetworkConnectionRequired());
            assertFalse(voices.get("Mateo").isNetworkConnectionRequired());

            assertTrue(tts.isLanguageAvailable(new Locale("en")) >= TextToSpeech.LANG_AVAILABLE);
            assertTrue(tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE);
            assertTrue(tts.isLanguageAvailable(new Locale("es")) >= TextToSpeech.LANG_AVAILABLE);
            assertTrue(
                tts.isLanguageAvailable(Locale.forLanguageTag("es-MX")) >=
                    TextToSpeech.LANG_AVAILABLE
            );
            assertEquals(
                TextToSpeech.LANG_NOT_SUPPORTED,
                tts.isLanguageAvailable(new Locale("ru"))
            );
            assertTrue(tts.setLanguage(new Locale("es")) >= TextToSpeech.LANG_AVAILABLE);
            assertEquals("Mateo", tts.getVoice().getName());
            assertTrue(
                tts.setLanguage(Locale.forLanguageTag("es-MX")) >= TextToSpeech.LANG_AVAILABLE
            );
            assertEquals("Mateo", tts.getVoice().getName());
            assertTrue(tts.setLanguage(new Locale("en")) >= TextToSpeech.LANG_AVAILABLE);
            assertEquals("Slt", tts.getVoice().getName());
            assertTrue(tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE);
            assertEquals("Slt", tts.getVoice().getName());

            Bundle volume = new Bundle();
            volume.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);

            assertEquals(TextToSpeech.SUCCESS, tts.setVoice(voices.get("Mateo")));
            File spanish = synthesize(
                tts,
                context,
                "Prueba de voz Mateo sin conexión.",
                volume,
                "spanish"
            );
            AudioStats spanishStats = analyzeWave(spanish);
            Log.i(TAG, "Mateo stats " + spanishStats);
            assertTrue(spanishStats.sampleCount > 1_000);
            assertTrue(spanishStats.peak > 1_000);
            assertTrue("Spanish output clips excessively", spanishStats.clippedRatio < 0.01);

            assertEquals(TextToSpeech.SUCCESS, tts.setVoice(voices.get("Slt")));
            assertEquals(TextToSpeech.SUCCESS, tts.setSpeechRate(1.0f));
            File englishDefault = synthesize(
                tts,
                context,
                "Global Connect verifies clear offline speech on the payment terminal.",
                volume,
                "english-default"
            );
            AudioStats defaultStats = analyzeWave(englishDefault);
            Log.i(TAG, "Slt rate=1.0 stats " + defaultStats);
            assertTrue(defaultStats.peak > 1_000);
            assertTrue("English output clips excessively", defaultStats.clippedRatio < 0.01);

            assertEquals(TextToSpeech.SUCCESS, tts.setSpeechRate(0.85f));
            File englishSlow = synthesize(
                tts,
                context,
                "Global Connect verifies clear offline speech on the payment terminal.",
                volume,
                "english-slow"
            );
            AudioStats slowStats = analyzeWave(englishSlow);
            Log.i(
                TAG,
                "Slt rate=0.85 stats " + slowStats +
                    " durationRatio=" +
                    ((double) slowStats.sampleCount / defaultStats.sampleCount)
            );
            assertTrue(
                "0.85 speech rate did not increase duration enough: default=" +
                    defaultStats.sampleCount + " slow=" + slowStats.sampleCount,
                slowStats.sampleCount > defaultStats.sampleCount * 1.08
            );
            assertTrue("Slower English output clips excessively", slowStats.clippedRatio < 0.01);
        } finally {
            tts.shutdown();
        }
    }

    private static File synthesize(
        TextToSpeech tts,
        Context context,
        String text,
        Bundle parameters,
        String label
    ) throws Exception {
        File directory = context.getExternalCacheDir();
        if (directory == null) {
            directory = context.getCacheDir();
        }
        File output = new File(directory, "rhvoice-" + label + ".wav");
        if (output.exists() && !output.delete()) {
            throw new IOException("Cannot remove previous output " + output);
        }

        String utteranceId = "test-" + label + "-" + System.nanoTime();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(TextToSpeech.ERROR);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                if (utteranceId.equals(id)) {
                    status.set(TextToSpeech.SUCCESS);
                    completed.countDown();
                }
            }

            @Override
            public void onError(String id) {
                if (utteranceId.equals(id)) {
                    completed.countDown();
                }
            }

            @Override
            public void onError(String id, int errorCode) {
                if (utteranceId.equals(id)) {
                    status.set(errorCode);
                    completed.countDown();
                }
            }
        });
        assertEquals(
            TextToSpeech.SUCCESS,
            tts.synthesizeToFile(text, parameters, output, utteranceId)
        );
        assertTrue("Synthesis timed out for " + label, completed.await(90, TimeUnit.SECONDS));
        assertEquals("Synthesis failed for " + label, TextToSpeech.SUCCESS, status.get());
        assertTrue("WAV output is empty for " + label, output.length() > 44);
        return output;
    }

    private static AudioStats analyzeWave(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
        }

        int dataOffset = findWaveData(bytes);
        int samples = Math.max(0, (bytes.length - dataOffset) / 2);
        int peak = 0;
        int clipped = 0;
        for (int offset = dataOffset; offset + 1 < bytes.length; offset += 2) {
            short sample = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
            int absolute = sample == Short.MIN_VALUE ? 32768 : Math.abs(sample);
            peak = Math.max(peak, absolute);
            if (absolute >= 32760) {
                clipped++;
            }
        }
        return new AudioStats(samples, peak, samples == 0 ? 1.0 : ((double) clipped) / samples);
    }

    private static int findWaveData(byte[] bytes) throws IOException {
        for (int index = 12; index + 8 <= bytes.length; ) {
            int length =
                (bytes[index + 4] & 0xff) |
                    ((bytes[index + 5] & 0xff) << 8) |
                    ((bytes[index + 6] & 0xff) << 16) |
                    ((bytes[index + 7] & 0xff) << 24);
            if (
                bytes[index] == 'd' && bytes[index + 1] == 'a' &&
                    bytes[index + 2] == 't' && bytes[index + 3] == 'a'
            ) {
                return index + 8;
            }
            index += 8 + Math.max(0, length);
        }
        throw new IOException("WAV data chunk not found");
    }

    private static final class AudioStats {
        final int sampleCount;
        final int peak;
        final double clippedRatio;

        AudioStats(int sampleCount, int peak, double clippedRatio) {
            this.sampleCount = sampleCount;
            this.peak = peak;
            this.clippedRatio = clippedRatio;
        }

        @Override
        public String toString() {
            return "samples=" + sampleCount + " peak=" + peak + " clippedRatio=" + clippedRatio;
        }
    }
}
