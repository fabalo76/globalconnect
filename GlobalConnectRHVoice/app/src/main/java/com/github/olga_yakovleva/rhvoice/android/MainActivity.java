/*
 * Copyright (C) 2026 Global Connect.
 * Licensed under the GNU General Public License version 2.
 */
package com.github.olga_yakovleva.rhvoice.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int padding = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            24,
            getResources().getDisplayMetrics()
        );

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.status_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(
            title,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        status.setPadding(0, padding, 0, padding);
        try {
            BundledDataInstaller.ensureInstalled(this);
            status.setText(R.string.status_ready);
        } catch (Exception error) {
            status.setText(R.string.status_failed);
        }
        content.addView(status);

        TextView details = new TextView(this);
        details.setText(R.string.status_details);
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        details.setTextIsSelectable(true);
        content.addView(details);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);
    }
}
