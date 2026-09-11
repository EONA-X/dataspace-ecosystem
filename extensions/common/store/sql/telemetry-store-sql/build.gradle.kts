/*
 *  Copyright (c) 2024 Amadeus SA
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Amadeus SA - initial implementation
 *
 */

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    implementation(project(":spi:telemetry-agent-spi"))
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.transaction)
    implementation(libs.edc.spi.transaction.datasource)
    implementation(libs.jackson.databind)
    implementation(libs.edc.sql.lease)
    implementation(libs.edc.sql.lease.spi)
    implementation(libs.edc.core.sql)
    implementation(libs.edc.lib.sql)
    implementation(libs.edc.sql.bootstrapper)

    testImplementation(libs.edc.core.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.postgres)
    testImplementation(testFixtures(project(":spi:telemetry-agent-spi")))
    testImplementation(testFixtures(libs.edc.core.sql))
    testImplementation(testFixtures(libs.edc.sql.lease))
    testImplementation(testFixtures(libs.edc.sql.test.fixtures))

}
