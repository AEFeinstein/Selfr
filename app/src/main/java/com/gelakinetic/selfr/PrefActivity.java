package com.gelakinetic.selfr;

import android.os.Bundle;
import android.view.MenuItem;

import com.example.android.supportv7.app.AppCompatPreferenceActivity;

@SuppressWarnings("deprecation")
public class PrefActivity extends AppCompatPreferenceActivity {

    /**
     * Called when this activity is created. Inflates the
     * preferences and enables the up button on the toolbar
     *
     * @param savedInstanceState Unused
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    /**
     * When the up button is pressed, finish this activity
     *
     * @param item The MenuItem selected
     * @return true if the press was handled, false otherwise
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                finish();
                return true;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }
}
