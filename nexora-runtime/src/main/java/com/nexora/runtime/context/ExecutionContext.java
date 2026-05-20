package com.nexora.runtime.context;

import com.nexora.core.intent.Intent;

import java.util.HashMap;
import java.util.Map;

public class ExecutionContext {

    private final Intent intent;
    private final Map<String, Object> data = new HashMap<>();

    public ExecutionContext(Intent intent) {
        this.intent = intent;
    }

    public Intent getIntent() {
        return intent;
    }

    public void put(String key, Object value){
        data.put(key,value);
    }

    public Map<String, Object> getAll() {
        return data;
    }
}
