import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin
import java.io.IOException
import java.net.URI

plugins {
    `java-library`
    id("com.bmuschko.docker-remote-api") version "9.3.4"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    alias(libs.plugins.edc.build)
    checkstyle
}

afterEvaluate {
    extensions.findByName("openApiMerger")?.let { ext ->
        (ext as com.rameshkp.openapi.merger.gradle.extensions.OpenApiMergerExtension).apply {
            openApi {
                openApiVersion.set("3.0.1")
                info {
                    title.set("Dataspace Ecosystem APIs")
                    description.set("Merged OpenAPI specification for all Dataspace Ecosystem extensions")
                    version.set(project.version.toString())
                }
            }
        }
    }
}

val annotationProcessorVersion: String by project
val dseWebsiteUrl: String by project
val dseScmConnection: String by project
val dseScmUrl: String by project

val loadToKind = project.hasProperty("loadToKind")

allprojects {

    apply(plugin = "${group}.edc-build")
    apply(plugin = "org.eclipse.edc.autodoc")

    // configure which version of the annotation processor to use. defaults to the same version as the plugin
    configure<org.eclipse.edc.plugins.autodoc.AutodocExtension> {
        processorVersion.set(annotationProcessorVersion)
        outputDirectory.set(project.layout.buildDirectory.asFile.get())
    }

    configure<org.eclipse.edc.plugins.edcbuild.extensions.BuildExtension> {
        pom {
            // this is actually important, so we can publish under the correct GID
            groupId = project.group.toString()
            projectName.set(project.name)
            description.set("edc :: ${project.name}")
            projectUrl.set(dseWebsiteUrl)
            scmConnection.set(dseScmConnection)
            scmUrl.set(dseScmUrl)
        }
    }

    configure<CheckstyleExtension> {
        toolVersion = "10.12.4"
        configFile = rootProject.file("resources/checkstyle.xml")
        configDirectory.set(rootProject.file("resources"))
        isIgnoreFailures = false

        val suppressionFile = rootProject.file("resources/checkstyle-suppressions.xml")
        configProperties = mapOf(
            "org.checkstyle.google.suppressionfilter.config" to suppressionFile.absolutePath,
            "checkstyle.suppressions.file" to suppressionFile.absolutePath
        )
    }

    // Force secure Netty version to fix vulnerable component: netty-codec-http2
    configurations.all {
        resolutionStrategy {
            force("io.netty:netty-codec-http2:4.2.4.Final")
        }
    }

    // EDC 0.15.0's verifiable-credentials module bumped com.networknt:json-schema-validator
    // to 2.0.0 (breaking API change), which wins over mockserver-netty's 1.x requirement by
    // default Gradle resolution. mockserver-core 5.15.0 is compiled against the 1.x API
    // (SpecVersion$VersionFlag), so under 2.0.0 it throws NoClassDefFoundError while
    // deserializing any expectation, crashing the connection mid-request. The client then
    // just sees SocketConnectionException: "Channel handler removed before valid response
    // has been received". Pin to a 1.x version (matching what worked pre-0.15.0) in test
    // configurations only, since production code needs the 2.0.0 API for real credential
    // validation.
    configurations.matching { it.name.contains("test", ignoreCase = true) }.all {
        resolutionStrategy {
            force("com.networknt:json-schema-validator:1.5.8")
        }
    }
}

// Single download of the OpenTelemetry agent, shared by every module's downloadOtel task.
// Uses externalLibs/ when the jar is provided there, otherwise downloads it. Run "clean" to force a re-download.
val fetchOtelAgent = tasks.register("fetchOtelAgent") {
    val otelFileName = "opentelemetry-javaagent.jar"
    // Override with -PotelAgentUrl=... to use a mirror when GitHub is unreachable from the runner.
    val openTelemetryAgentUrl = findProperty("otelAgentUrl")?.toString()
        ?: "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.9.0/$otelFileName"
    val localAgent = rootDir.resolve("externalLibs").resolve(otelFileName)
    val outputJar = layout.buildDirectory.file("otel/$otelFileName")

    inputs.property("url", openTelemetryAgentUrl)
    inputs.files(localAgent).optional()
    outputs.file(outputJar)

    doLast {
        val dest = outputJar.get().asFile
        dest.parentFile.mkdirs()
        if (localAgent.exists()) {
            localAgent.copyTo(dest, overwrite = true)
        } else {
            logger.lifecycle("Downloading OpenTelemetry Agent from $openTelemetryAgentUrl")
            val maxAttempts = 5
            for (attempt in 1..maxAttempts) {
                try {
                    val connection = URI(openTelemetryAgentUrl).toURL().openConnection()
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                    connection.getInputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                    break
                } catch (e: IOException) {
                    logger.warn("OpenTelemetry Agent download attempt $attempt/$maxAttempts failed: $e")
                    if (attempt == maxAttempts) throw GradleException("Cannot download $openTelemetryAgentUrl", e)
                    Thread.sleep(attempt * 2000L)
                }
            }
        }
    }
}

subprojects {
    afterEvaluate {
        val vaultType: String = project.findProperty("vaultType") as? String ?: "hashicorp"

        // Skip building Docker images for non-selected vault variants
        val shouldSkipVaultVariant = when {
            vaultType == "both" -> false  // Build all variants when vaultType=both
            project.name.contains("-postgresql-azurevault") && vaultType != "azure" -> true
            project.name.contains("-postgresql-hashicorpvault") && vaultType != "hashicorp" -> true
            else -> false
        }

        if (project.plugins.hasPlugin("com.github.johnrengelman.shadow") && file("${project.projectDir}/Dockerfile").exists() && !shouldSkipVaultVariant) {
            val buildDir = project.layout.buildDirectory.get().asFile
            val otelFileName = "opentelemetry-javaagent.jar"
            val agentFileOnBuildDirectory = buildDir.resolve(otelFileName)

            val dockerContextDir = project.projectDir
            val dockerFile = file("$dockerContextDir/Dockerfile")
            val imageName = "${project.name}:latest"

            // The agent is fetched once by the root project (fetchOtelAgent); each module only copies it.
            val downloadOtel = tasks.register<Copy>("downloadOtel") {
                dependsOn(rootProject.tasks.named("fetchOtelAgent"))
                from(rootProject.tasks.named("fetchOtelAgent"))
                into(buildDir)
            }

            val podmanTask = tasks.register("podmanize", Exec::class) {
                description = "Build container image with Podman"
                group = "build"

                val jarFile = "./build/libs/${project.name}.jar"
                val otelFile = "./build/$otelFileName"
                val platform = System.getProperty("platform")

                val commandLineArgs = mutableListOf(
                    "podman", "build",
                    "--build-arg", "JAR=$jarFile",
                    "--build-arg", "OTEL_JAR=$otelFile",
                    "-t", imageName,
                    "-f", dockerFile,
                    dockerContextDir
                )

                if(platform != null) {
                    commandLineArgs.add("--platform")
                    commandLineArgs.add(platform)
                }

                commandLine(commandLineArgs)

                doLast {
                    exec {
                        commandLine("podman", "save", "-o", "${dockerContextDir.path}/image.tar", imageName)
                    }
                }
            }
            podmanTask.configure {
                dependsOn(tasks.named(ShadowJavaPlugin.SHADOW_JAR_TASK_NAME))
                dependsOn(downloadOtel)
            }

            val loadToKindTask = tasks.register("loadToKind") {
                doFirst {
                    exec {
                        commandLine("kind", "load", "image-archive", "${dockerContextDir.path}/image.tar", "-n", "dse-cluster")
                        println("Image loaded to kind: $imageName")
                    }
                }
            }
            loadToKindTask.configure {
                dependsOn(podmanTask)
            }

            // Comma-separated list of tags to build/push under, e.g. "latest,20260722-1530".
            // Accepts either the plural "imageTags" or the singular "imageTag".
            fun imageTags(): List<String> {
                val raw = project.findProperty("imageTags")?.toString()
                    ?: project.findProperty("imageTag")?.toString()
                    ?: "latest"
                return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            val dockerTask: DockerBuildImage = tasks.create("dockerize", DockerBuildImage::class) {
                project.plugins.apply("com.bmuschko.docker-remote-api")

                val registryHost = project.findProperty("registryHost")?.toString() ?: "localhost"
                val registryNs = project.findProperty("registryNs")?.toString() ?: "local"

                images.addAll(imageTags().map { "${registryHost}/${registryNs}/${project.name}:${it}" })
                // specify platform with the -Dplatform flag:
                if (System.getProperty("platform") != null)
                    platform.set(System.getProperty("platform"))
                buildArgs.put("JAR", "build/libs/${project.name}.jar")
                buildArgs.put("OTEL_JAR", "build/${agentFileOnBuildDirectory.name}")
                inputDir.set(file(dockerContextDir))
            }
            // make sure  always runs after "dockerize" and after "copyOtel"
            dockerTask.dependsOn(tasks.named(ShadowJavaPlugin.SHADOW_JAR_TASK_NAME))
                .dependsOn(downloadOtel)

            // Only create dockerPushTask if not running locally
            val registryHostForPush = project.findProperty("registryHost")?.toString() ?: "localhost"
            if (registryHostForPush != "localhost") {
                val dockerPushTask: DockerPushImage = tasks.create("dockerPush", DockerPushImage::class) {
                    group = "distribution"
                    description = "Push Docker image to registry"
                    project.plugins.apply("com.bmuschko.docker-remote-api")

                    val registryHost = project.findProperty("registryHost")?.toString() ?: "localhost"
                    val registryNs = project.findProperty("registryNs")?.toString() ?: "local"

                    images.addAll(imageTags().map { "${registryHost}/${registryNs}/${project.name}:${it}" })
                }
                // Ensure push happens after build
                dockerPushTask.dependsOn(dockerTask)
            }


        }
    }
}

buildscript {
    dependencies {
        val edcGradlePluginsVersion: String by project
        val version: String by project
        classpath("org.eclipse.edc.edc-build:org.eclipse.edc.edc-build.gradle.plugin:${edcGradlePluginsVersion}")
        classpath("org.eclipse.edc.autodoc:org.eclipse.edc.autodoc.gradle.plugin:$version")
    }
}
