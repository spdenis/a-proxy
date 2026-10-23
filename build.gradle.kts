plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.testing"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.dependencies.get().toString())
    }
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.cloud.gateway.webmvc)
    implementation(libs.httpclient5)
    implementation(libs.caffeine)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.wiremock)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // never load the developer's ./user.properties into tests
    systemProperty("aproxy.user-properties", "build/no-user.properties")
}

// Summarise a capture run: ./gradlew analyzeCaptures --args="captures/<run> [--only=regex] [--values=N] [--no-values] [--brief]"
tasks.register<JavaExec>("analyzeCaptures") {
    group = "autosys"
    description = "Prints a compact summary of recorded AutoSys UI traffic (newest run under ./captures by default)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.testing.aproxy.capture.CaptureAnalyzer"
    workingDir = projectDir
}
