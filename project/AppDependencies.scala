import play.sbt.PlayImport._
import sbt._

object AppDependencies {

  val playVersion = "play-28"
  val mongoVersion = "0.74.0"

  private val playSuffix = "-play-30"
  private val bootstrapVersion = "8.4.0"
  private val hmrcMongoVersion = "1.7.0"

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc.mongo"                          %% s"hmrc-mongo$playSuffix"         % hmrcMongoVersion,
    "uk.gov.hmrc"                                %% s"bootstrap-backend$playSuffix"  % bootstrapVersion,
    "uk.gov.hmrc"                                %% s"domain$playSuffix"         % "9.0.0",
    "uk.gov.hmrc"                                %% "reactive-circuit-breaker"   % "4.1.0",
    "uk.gov.hmrc"                                %% "tax-year"                   % "4.0.0",
    "com.typesafe.play"                          %% "play-json-joda"             % "2.9.4",
    "com.github.ghik"                            %  "silencer-lib"               % "1.7.14" % Provided cross CrossVersion.full,
    compilerPlugin("com.github.ghik" %  "silencer-plugin"            % "1.7.14" cross CrossVersion.full)
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc" %% s"bootstrap-test$playSuffix" % bootstrapVersion,
    "uk.gov.hmrc.mongo" %% s"hmrc-mongo-test$playSuffix" % hmrcMongoVersion,
    "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.0",
//    "org.scalatestplus" %% "scalacheck-1-17" % "3.2.17.0",
    "org.mockito" %% "mockito-scala-scalatest" % "1.17.30",
    "org.apache.pekko" %% "pekko-testkit" % "1.0.2",
//    "uk.gov.hmrc"             %% s"bootstrap-test-$playVersion"   % "7.19.0",
//    "com.typesafe.akka"       %% "akka-testkit"                   % "2.6.20",
//    "com.github.tomakehurst"  %  "wiremock-jre8"                  % "2.35.0",
//    "uk.gov.hmrc.mongo"       %% s"hmrc-mongo-test-$playVersion"  % mongoVersion,
//    "org.mockito"             %  "mockito-all"                    % "1.10.19",
//    "org.scalatestplus.play"  %% "scalatestplus-play"             % "5.1.0",
//    "org.scalatestplus"       %% "scalatestplus-mockito"          % "1.0.0-M2",
//    "com.typesafe.play"       %% "play-test"                      % "2.8.19",
//    "com.vladsch.flexmark"    %  "flexmark-all"                   % "0.35.10"
  ).map(_ % "test")

  val jacksonVersion         = "2.13.2"
  val jacksonDatabindVersion = "2.13.2.2"

  val jacksonOverrides = Seq(
    "com.fasterxml.jackson.core"     % "jackson-core",
    "com.fasterxml.jackson.core"     % "jackson-annotations",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310"
  ).map(_ % jacksonVersion)

  val jacksonDatabindOverrides = Seq(
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion
  )

  val akkaSerializationJacksonOverrides = Seq(
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-cbor",
    "com.fasterxml.jackson.module"     %  "jackson-module-parameter-names",
    "com.fasterxml.jackson.module"     %% "jackson-module-scala",
  ).map(_ % jacksonVersion)

  val all: Seq[ModuleID] = compile ++ jacksonDatabindOverrides ++ jacksonOverrides ++ akkaSerializationJacksonOverrides ++ test

}
