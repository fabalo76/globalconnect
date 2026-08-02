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

public final class SampleTextActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String language = getIntent().getStringExtra("language");
        boolean spanish = "es".equalsIgnoreCase(language) || "spa".equalsIgnoreCase(language);
        Intent result = new Intent();
        result.putExtra(
            TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
            getString(spanish ? R.string.spanish_sample : R.string.english_sample)
        );
        setResult(TextToSpeech.LANG_AVAILABLE, result);
        finish();
    }
}
