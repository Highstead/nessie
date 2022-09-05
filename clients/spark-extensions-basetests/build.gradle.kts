/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  `maven-publish`
  signing
  scala
  `nessie-conventions`
}

val sparkScala = getSparkScalaVersionsForProject()

dependencies {
  // picks the right dependencies for scala compilation
  forScala(sparkScala.scalaVersion)

  implementation(platform(nessieRootProject()))
  implementation(nessieProjectPlatform("nessie-deps-testing", gradle))
  compileOnly(nessieProjectPlatform("nessie-deps-build-only", gradle))
  annotationProcessor(nessieProjectPlatform("nessie-deps-build-only", gradle))
  implementation(platform("org.junit:junit-bom"))

  implementation(nessieProject("nessie-spark-extensions-grammar"))
  compileOnly("org.apache.spark:spark-hive_${sparkScala.scalaMajorVersion}") {
    forSpark(sparkScala.sparkVersion)
  }
  compileOnly("org.eclipse.microprofile.openapi:microprofile-openapi-api")
  implementation(nessieClientForIceberg())

  implementation("org.apache.spark:spark-sql_${sparkScala.scalaMajorVersion}") {
    forSpark(sparkScala.sparkVersion)
  }
  implementation("org.eclipse.microprofile.openapi:microprofile-openapi-api")

  compileOnly("org.immutables:value-annotations")
  annotationProcessor("org.immutables:value-processor")

  implementation("org.assertj:assertj-core")
  implementation("org.junit.jupiter:junit-jupiter-api")
  implementation("org.junit.jupiter:junit-jupiter-params")
}