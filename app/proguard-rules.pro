# P-Meeting keeps all media inside the app's private storage. No network
# libraries are used, so there is nothing to strip. Rules are kept minimal.
-keep class com.prakash.pmeeting.recording.** { *; }
