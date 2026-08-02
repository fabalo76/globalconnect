/*
 * Standalone Global Connect Android TextToSpeech service.
 * Based on the RHVoice Android service.
 *
 * Copyright (C) 2013-2022 Olga Yakovleva and RHVoice contributors.
 * Copyright (C) 2026 Global Connect.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2.
 */
package com.github.olga_yakovleva.rhvoice.android;

import android.media.AudioFormat;
import android.os.Bundle;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.util.Log;

import com.github.olga_yakovleva.rhvoice.LanguageInfo;
import com.github.olga_yakovleva.rhvoice.LogLevel;
import com.github.olga_yakovleva.rhvoice.Logger;
import com.github.olga_yakovleva.rhvoice.SynthesisParameters;
import com.github.olga_yakovleva.rhvoice.TTSClient;
import com.github.olga_yakovleva.rhvoice.TTSEngine;
import com.github.olga_yakovleva.rhvoice.VoiceInfo;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RHVoiceService extends TextToSpeechService {
    private static final String TAG = "GlobalConnectRHVoice";
    private static final String ENGLISH_VOICE = "Slt";
    private static final String SPANISH_VOICE = "Mateo";
    private static final Locale ENGLISH_LOCALE = Locale.US;
    private static final Locale SPANISH_LOCALE = Locale.forLanguageTag("es-MX");

    private final Object engineLock = new Object();
    private final Map<String, VoiceInfo> voiceIndex = new LinkedHashMap<>();
    private volatile boolean speaking;
    private volatile String currentVoiceName = ENGLISH_VOICE;
    private TTSEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(
            TAG,
            "Engine service initializing version=" + BuildConfig.VERSION_NAME +
                " dataVersion=" + BuildConfig.BUNDLED_DATA_VERSION +
                " upstream=" + BuildConfig.UPSTREAM_COMMIT
        );
        initializeEngine();
    }

    private void initializeEngine() {
        synchronized (engineLock) {
            shutdownEngineLocked();
            voiceIndex.clear();
            try {
                File dataRoot = BundledDataInstaller.ensureInstalled(this);
                File configRoot = new File(getFilesDir(), "config");
                if (!configRoot.isDirectory() && !configRoot.mkdirs()) {
                    throw new IllegalStateException("Cannot create config directory");
                }
                engine = new TTSEngine(
                    "",
                    configRoot.getAbsolutePath(),
                    BundledDataInstaller.getResourcePaths(dataRoot),
                    "",
                    DiagnosticLogger.INSTANCE
                );
                for (VoiceInfo voice : engine.getVoices()) {
                    voiceIndex.put(voice.getName().toLowerCase(Locale.US), voice);
                }
                validateDiscoveredVoices();
                Log.i(TAG, "Engine initialized voices=" + voiceIndex.keySet());
            } catch (Exception error) {
                Log.e(TAG, "Engine initialization failed", error);
                shutdownEngineLocked();
                voiceIndex.clear();
            }
        }
    }

    private void validateDiscoveredVoices() {
        if (voiceIndex.size() != 2) {
            throw new IllegalStateException("Expected exactly two voices, found " + voiceIndex.size());
        }
        requireVoice(ENGLISH_VOICE, "eng");
        requireVoice(SPANISH_VOICE, "spa");
    }

    private void requireVoice(String name, String language) {
        VoiceInfo voice = voiceIndex.get(name.toLowerCase(Locale.US));
        if (
            voice == null || voice.getLanguage() == null ||
                !language.equalsIgnoreCase(voice.getLanguage().getAlpha3Code())
        ) {
            throw new IllegalStateException("Missing or mismatched voice " + name);
        }
    }

    @Override
    protected String[] onGetLanguage() {
        if (SPANISH_VOICE.equalsIgnoreCase(currentVoiceName)) {
            return new String[]{"spa", "MEX", ""};
        }
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onIsLanguageAvailable(String language, String country, String variant) {
        if (!isSupportedLanguage(language)) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        synchronized (engineLock) {
            if (engine == null) {
                return TextToSpeech.LANG_MISSING_DATA;
            }
        }
        return TextUtils.isEmpty(country)
            ? TextToSpeech.LANG_AVAILABLE
            : TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected int onLoadLanguage(String language, String country, String variant) {
        int availability = onIsLanguageAvailable(language, country, variant);
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            currentVoiceName = isSpanish(language) ? SPANISH_VOICE : ENGLISH_VOICE;
            Log.i(
                TAG,
                "Locale loaded language=" + safe(language) +
                    " country=" + safe(country) +
                    " voice=" + currentVoiceName
            );
        }
        return availability;
    }

    @Override
    public List<Voice> onGetVoices() {
        synchronized (engineLock) {
            if (engine == null) {
                return Collections.emptyList();
            }
        }
        List<Voice> result = new ArrayList<>(2);
        result.add(
            new Voice(
                ENGLISH_VOICE,
                ENGLISH_LOCALE,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                false,
                Collections.emptySet()
            )
        );
        result.add(
            new Voice(
                SPANISH_VOICE,
                SPANISH_LOCALE,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                false,
                Collections.emptySet()
            )
        );
        Log.i(TAG, "Reporting offline voices=[Slt(en-US), Mateo(es-MX)]");
        return result;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        if (onIsLanguageAvailable(language, country, variant) < TextToSpeech.LANG_AVAILABLE) {
            return null;
        }
        return isSpanish(language) ? SPANISH_VOICE : ENGLISH_VOICE;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        if (name == null) {
            return TextToSpeech.ERROR;
        }
        return (
            ENGLISH_VOICE.equalsIgnoreCase(name) ||
                SPANISH_VOICE.equalsIgnoreCase(name)
        ) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String name) {
        int result = onIsValidVoiceName(name);
        if (result == TextToSpeech.SUCCESS) {
            currentVoiceName = canonicalVoiceName(name);
            Log.i(TAG, "Voice loaded voice=" + currentVoiceName);
        }
        return result;
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String language = request.getLanguage();
        String country = request.getCountry();
        String requestedVoice = request.getVoiceName();
        String selectedVoice = selectVoice(language, requestedVoice);
        int characterCount = request.getText() == null ? 0 : request.getText().length();

        if (selectedVoice == null || !isSupportedLanguage(language)) {
            Log.w(
                TAG,
                "Synthesis rejected unsupported language=" + safe(language) +
                    " country=" + safe(country) +
                    " voice=" + safe(requestedVoice) +
                    " chars=" + characterCount
            );
            callback.error();
            return;
        }

        synchronized (engineLock) {
            if (engine == null) {
                Log.e(TAG, "Synthesis failed because engine is not initialized");
                callback.error();
                return;
            }
            try {
                speaking = true;
                currentVoiceName = selectedVoice;
                SynthesisParameters parameters = new SynthesisParameters();
                parameters.setVoiceProfile(selectedVoice);
                parameters.setRate(clamp(request.getSpeechRate() / 100.0, 0.1, 3.0));
                parameters.setPitch(clamp(request.getPitch() / 100.0, 0.5, 2.0));
                parameters.setVolume(BuildConfig.GLOBAL_CONNECT_OUTPUT_GAIN);

                Bundle requestParameters = request.getParams();
                float requestedVolume = requestParameters == null
                    ? 1.0f
                    : requestParameters.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                Log.i(
                    TAG,
                    "Synthesis start language=" + safe(language) +
                        " country=" + safe(country) +
                        " voice=" + selectedVoice +
                        " chars=" + characterCount +
                        " rate=" + parameters.getRate() +
                        " requestedVolume=" + requestedVolume +
                        " engineGain=" + BuildConfig.GLOBAL_CONNECT_OUTPUT_GAIN
                );

                CallbackPlayer player = new CallbackPlayer(callback);
                engine.speak(request.getText().toString(), parameters, player);
                callback.done();
                Log.i(TAG, "Synthesis complete voice=" + selectedVoice + " chars=" + characterCount);
            } catch (Exception error) {
                Log.e(
                    TAG,
                    "Synthesis failure voice=" + selectedVoice + " chars=" + characterCount,
                    error
                );
                callback.error();
            } finally {
                speaking = false;
            }
        }
    }

    private String selectVoice(String language, String requestedVoice) {
        if (!TextUtils.isEmpty(requestedVoice)) {
            if (SPANISH_VOICE.equalsIgnoreCase(requestedVoice)) {
                return isSpanish(language) ? SPANISH_VOICE : null;
            }
            if (ENGLISH_VOICE.equalsIgnoreCase(requestedVoice)) {
                return isEnglish(language) ? ENGLISH_VOICE : null;
            }
            return null;
        }
        if (isSpanish(language)) {
            return SPANISH_VOICE;
        }
        if (isEnglish(language)) {
            return ENGLISH_VOICE;
        }
        return null;
    }

    @Override
    protected void onStop() {
        speaking = false;
        Log.i(TAG, "Synthesis stop requested");
    }

    @Override
    public void onDestroy() {
        synchronized (engineLock) {
            speaking = false;
            shutdownEngineLocked();
            voiceIndex.clear();
        }
        Log.i(TAG, "Engine service destroyed");
        super.onDestroy();
    }

    private void shutdownEngineLocked() {
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
    }

    private static boolean isSupportedLanguage(String language) {
        return isEnglish(language) || isSpanish(language);
    }

    private static boolean isEnglish(String language) {
        return "en".equalsIgnoreCase(language) || "eng".equalsIgnoreCase(language);
    }

    private static boolean isSpanish(String language) {
        return "es".equalsIgnoreCase(language) || "spa".equalsIgnoreCase(language);
    }

    private static String canonicalVoiceName(String name) {
        return SPANISH_VOICE.equalsIgnoreCase(name) ? SPANISH_VOICE : ENGLISH_VOICE;
    }

    private static String safe(String value) {
        return TextUtils.isEmpty(value) ? "(none)" : value;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class CallbackPlayer implements TTSClient {
        private final SynthesisCallback callback;
        private int sampleRate;

        CallbackPlayer(SynthesisCallback callback) {
            this.callback = callback;
        }

        @Override
        public boolean setSampleRate(int value) {
            if (sampleRate != 0) {
                return sampleRate == value;
            }
            sampleRate = value;
            return callback.start(value, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS;
        }

        @Override
        public boolean playSpeech(short[] samples) {
            if (!speaking || sampleRate == 0) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            buffer.asShortBuffer().put(samples);
            byte[] bytes = buffer.array();
            int maximum = callback.getMaxBufferSize();
            int offset = 0;
            while (offset < bytes.length) {
                if (!speaking) {
                    return false;
                }
                int count = Math.min(maximum, bytes.length - offset);
                if (callback.audioAvailable(bytes, offset, count) != TextToSpeech.SUCCESS) {
                    return false;
                }
                offset += count;
            }
            return true;
        }
    }

    private enum DiagnosticLogger implements Logger {
        INSTANCE;

        @Override
        public void log(String subtag, LogLevel level, String message) {
            String tag = "RHVoiceCore/" + subtag;
            switch (level) {
                case ERROR:
                    Log.e(tag, message);
                    break;
                case WARNING:
                    Log.w(tag, message);
                    break;
                case INFO:
                    Log.i(tag, message);
                    break;
                case DEBUG:
                    if (BuildConfig.DEBUG) {
                        Log.d(tag, message);
                    }
                    break;
                default:
                    if (BuildConfig.DEBUG) {
                        Log.v(tag, message);
                    }
                    break;
            }
        }
    }
}
