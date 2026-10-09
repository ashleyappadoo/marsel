import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Clé d'upload Google Play : lue depuis keystore.properties (racine du projet,
// JAMAIS versionné — cf. .gitignore). Absent → release non signée : on peut
// alors signer avec l'assistant Android Studio (Build → Generate Signed Bundle).
// Lu via providers.fileContents : fichier suivi par Gradle comme entrée de
// configuration (avec le cache de configuration, un keystore.properties
// ajouté après coup est bien pris en compte).
val keystoreProps = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText.orNull?.let { load(it.reader()) }
}
fun keystoreProp(key: String): String =
    requireNotNull(keystoreProps.getProperty(key)) { "keystore.properties : clé « $key » manquante" }

android {
    // namespace = package Kotlin/R interne, indépendant de l'identifiant Play.
    namespace = "com.example.marsel"
    compileSdk = 36

    defaultConfig {
        // Identifiant Google Play : unique et DÉFINITIF une fois publié.
        // (« com.example.* » est refusé par la Play Console.)
        applicationId = "com.marselapp.marsel"
        minSdk = 24
        targetSdk = 36
        // À incrémenter à CHAQUE envoi sur la Play Console.
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProp("storeFile"))
                storePassword = keystoreProp("storePassword")
                keyAlias = keystoreProp("keyAlias")
                keyPassword = keystoreProp("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // Chantier confidentialité (TODO.md §1) : FragmentActivity requis par
    // BiometricPrompt (complément du mot de passe local, pas un remplacement).
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
