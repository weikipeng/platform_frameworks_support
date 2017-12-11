/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.dsl.LintOptions
import net.ltgt.gradle.errorprone.ErrorProneBasePlugin
import net.ltgt.gradle.errorprone.ErrorProneToolChain
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.util.*

/**
 * Support library specific com.android.library plugin that sets common configurations needed for
 * support library modules.
 */
class SupportAndroidLibraryPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val supportLibraryExtension = project.extensions.create("supportLibrary",
                SupportLibraryExtension::class.java, project)
        apply(project, supportLibraryExtension)

        project.afterEvaluate {
            val library = project.extensions.findByType(LibraryExtension::class.java)
                    ?: return@afterEvaluate

            if (supportLibraryExtension.legacySourceLocation) {
                // We use a non-standard manifest path.
                library.sourceSets.getByName("main").manifest.srcFile("AndroidManifest.xml")

                // We use a non-standard test directory structure.
                val androidTest = library.sourceSets.getByName("androidTest")
                androidTest.setRoot("tests")
                androidTest.java.srcDir("tests/src")
                androidTest.res.srcDir("tests/res")
                androidTest.manifest.srcFile("tests/AndroidManifest.xml")
            }

            // Java 8 is only fully supported on API 24+ and not all Java 8 features are binary
            // compatible with API < 24, so use Java 7 for both source AND target.
            val javaVersion: JavaVersion;
            if (supportLibraryExtension.java8Library) {
                if (library.defaultConfig.minSdkVersion.apiLevel < 24) {
                    throw IllegalArgumentException("Libraries can only support Java 8 if "
                            + "minSdkVersion is 24 or higher");
                }
                javaVersion = JavaVersion.VERSION_1_8
            } else {
                javaVersion = JavaVersion.VERSION_1_7
            }

            library.compileOptions.setSourceCompatibility(javaVersion)
            library.compileOptions.setTargetCompatibility(javaVersion)
        }

        VersionFileWriterTask.setUpAndroidLibrary(project)

        project.apply(mapOf("plugin" to "com.android.library"))
        project.apply(mapOf("plugin" to ErrorProneBasePlugin::class.java))

        val library = project.extensions.findByType(LibraryExtension::class.java)
                ?: throw Exception("Failed to find Android extension")

        val currentSdk = project.property("currentSdk")
        when (currentSdk) {
            is Int -> library.compileSdkVersion(currentSdk)
            is String -> library.compileSdkVersion(currentSdk)
        }

        // Update the version meta-data in each Manifest.
        library.defaultConfig.addManifestPlaceholders(mapOf("target-sdk-version" to currentSdk))

        // Set test runner.
        library.defaultConfig.testInstrumentationRunner = INSTRUMENTATION_RUNNER

        library.testOptions.unitTests.isReturnDefaultValues = true

        // Use a local debug keystore to avoid build server issues.
        val key = ((project.rootProject.property("init") as Properties)
                .getValue("debugKeystore")) as File
        library.signingConfigs.findByName("debug")?.storeFile = key

        setUpLint(library.lintOptions, File(project.projectDir, "/lint-baseline.xml"))

        if (project.rootProject.property("usingFullSdk") as Boolean) {
            // Library projects don't run lint by default, so set up dependency.
            project.tasks.getByName("uploadArchives").dependsOn("lintRelease")
        }

        SourceJarTaskHelper.setUpAndroidProject(project, library)

        val toolChain = ErrorProneToolChain.create(project)
        library.buildTypes.create("errorProne")
        library.libraryVariants.all { libraryVariant ->
            if (libraryVariant.getBuildType().getName().equals("errorProne")) {
                @Suppress("DEPRECATION")
                libraryVariant.getJavaCompile().setToolChain(toolChain);

                @Suppress("DEPRECATION")
                val compilerArgs = libraryVariant.getJavaCompile().options.compilerArgs
                compilerArgs += arrayListOf(
                        "-XDcompilePolicy=simple", // Workaround for b/36098770

                        // Enforce the following checks.
                        "-Xep:MissingOverride:ERROR",
                        "-Xep:NarrowingCompoundAssignment:ERROR",
                        "-Xep:ClassNewInstance:ERROR",
                        "-Xep:ClassCanBeStatic:ERROR",
                        "-Xep:SynchronizeOnNonFinalField:ERROR",
                        "-Xep:OperatorPrecedence:ERROR"
                )
            }
        }
    }

    companion object {
        private val INSTRUMENTATION_RUNNER = "android.support.test.runner.AndroidJUnitRunner"
    }
}

private fun setUpLint(lintOptions: LintOptions, baseline: File) {
    // Always lint check NewApi as fatal.
    lintOptions.isAbortOnError = true
    lintOptions.isIgnoreWarnings = true

    // Write output directly to the console (and nowhere else).
    lintOptions.textOutput("stderr")
    lintOptions.textReport = true
    lintOptions.htmlReport = false

    // Format output for convenience.
    lintOptions.isExplainIssues = true
    lintOptions.isNoLines = false
    lintOptions.isQuiet = true

    lintOptions.error("NewApi")

    // Set baseline file for all legacy lint warnings.
    if (System.getenv("GRADLE_PLUGIN_VERSION") != null) {
        lintOptions.check("NewApi")
    } else {
        lintOptions.baseline(baseline)
    }
}