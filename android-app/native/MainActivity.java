package com.hanatrial.tripjepang;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LokasiBgPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
