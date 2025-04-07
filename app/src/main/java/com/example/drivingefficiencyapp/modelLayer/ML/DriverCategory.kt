package com.example.drivingefficiencyapp.modelLayer.ML

enum class DriverCategory(val label: String, val description: String) {
    ECO_FRIENDLY(
        "Eco-Friendly Driver",
        "You maintain steady speeds, accelerate gently and plan ahead to minimise fuel consumption."
    ),

    BALANCED(
        "Balanced Driver",
        "Your driving shows a good balance between efficiency and performance with room for minor improvements"
    ),

    MODERATE(
        "Moderate Driver",
        "Your driving is acceptable but could benefit from smoother acceleration and more consistent speeds"
    ),

    AGGRESSIVE(
        "Aggressive Driver",
        "Your driving style shows frequent rapid acceleration, hard braking, and inconsistent speeds."
    );
}