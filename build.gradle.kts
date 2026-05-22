import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        // R8 yang ter-bundle dengan AGP 8.7.3 adalah R8 8.7.18 — versi ini hanya
        // bisa parse Kotlin metadata sampai versi 2.0.x. Karena cloudstream:pre-release
        // sekarang dikompilasi dengan Kotlin 2.3 (metadata 2.3.0), tahap dexing akan
        // gagal dengan "WARNING: D8: An error occurred when parsing kotlin metadata"
        // dan exception "Should never be called" di setiap plugin.
        //
        // Solusi: override R8 dengan versi yang sudah mengenal Kotlin 2.3 metadata.
        // Per dokumen kompatibilitas Android, dukungan Kotlin 2.3 metadata baru ada
        // mulai R8 9.0.28+. 9.0.32 adalah versi minimum yang dipublikasikan ke
        // Google Maven dan terbukti aman.
        // Deklarasi ini HARUS di atas classpath AGP supaya Gradle memilih R8 ini
        // alih-alih R8 yang ter-pull transitively oleh AGP.
        // Referensi tabel kompatibilitas:
        //   https://developer.android.com/studio/build/kotlin-d8-r8-versions
        classpath("com.android.tools:r8:9.0.32")

        classpath("com.android.tools.build:gradle:8.7.3") // Jangan diganti ke versi terbaru, karena ada masalah dengan versi terbaru
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        // Match the Kotlin version that com.lagradost:cloudstream3:pre-release was
        // compiled with (currently 2.3.x). When the upstream JAR's metadata version
        // is newer than this compiler, every plugin breaks with
        //   "Module was compiled with an incompatible version of Kotlin"
        // followed by "Unresolved reference" cascades on every CS API call.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/tekuma25/Indostream")
        authors = listOf("TeKuma25")
    }

    android {
        namespace = "com.tekuma25"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35

        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }


        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
                allWarningsAsErrors.set(false)
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        
        // Cloudstream dependencies
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Other dependencies
        implementation(kotlin("stdlib")) // Untuk Kotlin Standard Library
        implementation("com.github.Blatzar:NiceHttp:0.4.13") // Untuk HTTP requests
        implementation("org.jsoup:jsoup:1.19.1") // Untuk parsing HTML
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0") // JANGAN DIGANTI ke versi terbaru, karena ada masalah dengan versi terbaru
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0") // Untuk serialisasi/deserialisasi JSON
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1") // Untuk coroutines di Android
        implementation("org.mozilla:rhino:1.8.0") // Untuk JavaScript engine
        implementation("me.xdrop:fuzzywuzzy:1.4.0") // Untuk fuzzy matching
        implementation("com.google.code.gson:gson:2.11.0") // Untuk serialisasi/deserialisasi JSON
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0") // Untuk serialisasi/deserialisasi JSON
        implementation("app.cash.quickjs:quickjs-android:0.9.2") // Untuk JavaScript engine
        implementation("com.squareup.okhttp3:okhttp:4.12.0") // Untuk HTTP requests
        implementation("androidx.core:core-ktx:1.16.0") // Untuk Log dan utilitas Android
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1") // Untuk coroutines

    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
