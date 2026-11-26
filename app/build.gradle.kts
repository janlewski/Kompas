plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.feelings.tracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kompas"
        minSdk = 26
        targetSdk = 36
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        error += setOf("MissingTranslation", "HardcodedText")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work)
    // The JNA JAR does not install libjnidispatch.so. Android then crashes in Native.<clinit>.
    implementation(libs.lazysodium) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val bannedGroupPrefixes = listOf(
    "com.google.firebase",
    "com.google.android.gms",
    "io.sentry",
    "com.squareup.okhttp3",
    "com.squareup.retrofit2",
    "io.ktor",
)
val bannedNameFragments = listOf("crashlytics", "play-services-ads")

tasks.register("checkBannedDependencies") {
    group = "verification"
    description = "Fails if a networking, ads, or crash-reporting artifact is on the release classpath."
    val configuration = configurations.named("releaseRuntimeClasspath")
    inputs.files(configuration)
    doLast {
        val found = configuration.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
            .filter { id ->
                bannedGroupPrefixes.any { id.group.startsWith(it) } ||
                    bannedNameFragments.any { id.name.contains(it, ignoreCase = true) }
            }
        if (found.isNotEmpty()) {
            throw GradleException(
                "Banned dependencies: " + found.joinToString { "${it.group}:${it.name}:${it.version}" },
            )
        }
    }
}

tasks.named("check") { dependsOn("checkBannedDependencies") }

ktlint {
    android.set(true)
    filter {
        exclude("**/schemas/**")
        exclude("**/build/**")
    }
}
