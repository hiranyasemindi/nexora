package com.nexora.planner.model;

import java.util.function.Predicate;

public class StepDefinition {

    private final String name;
    private final Predicate<String> matcher;

    public StepDefinition(String name, Predicate<String> matcher) {
        this.name = name;
        this.matcher = matcher;
    }

    public String getName() {
        return name;
    }

   public boolean matches(String intent){
        return matcher.test(intent);
   }
}
