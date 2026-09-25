package com.examprep.app;

import android.app.Application;

import com.examprep.app.data.api.ApiClient;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ApiClient.init(this);
    }
}
