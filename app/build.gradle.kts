plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="com.example.whispersubtitles"; compileSdk=35
 defaultConfig { applicationId="com.example.whispersubtitles"; minSdk=26; targetSdk=35; versionCode=1; versionName="1.0"
  externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
 }
 externalNativeBuild { cmake { path=file("src/main/cpp/CMakeLists.txt") } }
}
dependencies { implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.appcompat:appcompat:1.7.0"); implementation("com.google.android.material:material:1.12.0"); implementation("androidx.cardview:cardview:1.0.0") }