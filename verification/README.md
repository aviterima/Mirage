# Standalone core verification

CoreChecks.kt is the actual standalone harness used during this review. It compiles
with the actual application parser, MockState, LivePlan and engine sources. Only
the GoogleDirectionsRouteEngine HTTP client is replaced with RoutingStub.kt.
Do not include RoutingStub.kt in the Android app source set.

The Android Gradle test suite remains authoritative. This harness exists because
Gradle distribution download and GitHub writes were unavailable in the working
environment. It requires a Kotlin JVM compiler, stdlib, annotations and coroutines.
The review used the compiler/coroutines packaged with JetBrains' Kotlin Jupyter
kernel 0.19.0.944, with `-language-version 2.0`, on OpenJDK 17. This is not the exact
Android Gradle dependency set.

Compile the harness with these app files from `android/app/src/main/java`:

- com/mirage/spike/CommandParser.kt
- com/mirage/spike/MockState.kt
- com/mirage/spike/engine/{Models,MotionModel,DwellModel,FlightModel,TransitModel,DriveModel,Itinerary,LivePlan,ApiConfig}.kt

Then run CoreChecksKt (the output class derives from this filename). The harness
uses injected route factories and performs no Maps API calls. It tests actual
flows, timing, and parser outputs; it does not validate Android provider behavior,
Compose layout, microphone hardware, or native model packaging.
