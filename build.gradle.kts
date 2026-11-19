// Telepic root build script.
// Plugin versions are declared in gradle/libs.versions.toml and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Applied here (not `apply false`) because :app is the only module: the root project owns the
    // single detekt task set, and its source is pointed at :app below.
    alias(libs.plugins.detekt)
}

detekt {
    // Explicit, because the root project has no Kotlin source of its own: without this detekt finds
    // nothing and reports a vacuous pass.
    source.setFrom(files("app/src/main/java", "app/src/test/java"))
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    // The tree predates detekt, so the debt it already carries is recorded once rather than fixed in
    // this change. Anything *new* that trips a rule fails the build; shrink the baseline over time.
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
        txt.required.set(false)
    }
}
