package com.nexora.planner.engine;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.Step;
import com.nexora.planner.registry.PlanRegistry;

import java.util.List;

public class PlannerEngine {

    private final PlanRegistry registry;

    public PlannerEngine(PlanRegistry registry) {
        this.registry = registry;
    }

    public Plan createPlan (Intent intent){

        String goal = intent.getGoal();

        List<Step> steps = registry.getMatching(goal)
                .stream()
                .map(def -> new Step(def.getName()))
                .toList();

        return new Plan(steps);
    }
}
