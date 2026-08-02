/*
 * Copyright (C) 2026 Global Connect.
 * Licensed under the GNU General Public License version 2.
 */
package com.github.olga_yakovleva.rhvoice.android;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class UpgradeFixtureInstrumentedTest {
    @Test
    public void seedAppOwnedLegacyAndInterruptedDirectories() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "rhvoice-data");
        assertTrue(root.isDirectory() || root.mkdirs());
        assertTrue(new File(root, "bundle-1.17.2").mkdir());
        assertTrue(new File(root, "bundle-1.18.4-gc1.staging-interrupted").mkdir());
    }
}
