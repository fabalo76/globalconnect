/*
 * Copyright (C) 2013-2022 RHVoice contributors.
 * Copyright (C) 2026 Global Connect.
 * Licensed under the GNU General Public License version 2.
 */
package com.github.olga_yakovleva.rhvoice.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.ArrayList;

public final class CheckTTSData extends Activity {
    private static final String TAG = "RHVoiceCheckData";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent();
        ArrayList<String> available = new ArrayList<>();
        ArrayList<String> unavailable = new ArrayList<>();
        int status = TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL;
        try {
            BundledDataInstaller.ensureInstalled(this);
            available.add("eng-USA");
            available.add("spa-MEX");
            status = TextToSpeech.Engine.CHECK_VOICE_DATA_PASS;
            Log.i(TAG, "Bundled voice data check passed voices=[eng-USA, spa-MEX]");
        } catch (Exception error) {
            unavailable.add("eng-USA");
            unavailable.add("spa-MEX");
            Log.e(TAG, "Bundled voice data check failed", error);
        }
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable);
        setResult(status, result);
        finish();
    }
}
