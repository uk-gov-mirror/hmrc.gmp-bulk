import play.sbt.PlayImport.*
import sbt.*

object AppDependencies {

  private val playSuffix = "-play-30"
  private val bootstrapVersion = "10.2.0"
  private val hmrcMongoVersion = "2.9.0"
  private val pekkoVersion = "1.2.0"

  val compile: Seq[ModuleID] = Seq(
    ws,
    "uk.gov.hmrc.mongo"                          %% s"hmrc-mongo$playSuffix"        % hmrcMongoVersion,
    "uk.gov.hmrc"                                %% s"bootstrap-backend$playSuffix" % bootstrapVersion,
    "uk.gov.hmrc"                                %% s"domain$playSuffix"            % "13.0.0",
    "uk.gov.hmrc"                                %% "reactive-circuit-breaker"      % "6.1.0",
    "uk.gov.hmrc"                                %% "tax-year"                      % "6.0.0"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% s"bootstrap-test$playSuffix"  % bootstrapVersion,
    "uk.gov.hmrc.mongo"      %% s"hmrc-mongo-test$playSuffix" % hmrcMongoVersion,
    "org.scalatestplus.play" %% "scalatestplus-play"          % "7.0.2",
    "org.scalatestplus"      %% "mockito-5-18"                % "3.2.19.0",
    "org.apache.pekko"       %% "pekko-testkit"               % "1.2.1"
  ).map(_ % "test")

  val jacksonVersion         = "2.17.2"

  val jacksonOverrides = Seq(
    "com.fasterxml.jackson.core"       %  "jackson-databind",
    "com.fasterxml.jackson.core"       %  "jackson-core",
    "com.fasterxml.jackson.core"       %  "jackson-annotations",
    "com.fasterxml.jackson.datatype"   %  "jackson-datatype-jdk8",
    "com.fasterxml.jackson.datatype"   %  "jackson-datatype-jsr310",
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-cbor",
    "com.fasterxml.jackson.module"     %  "jackson-module-parameter-names",
    "com.fasterxml.jackson.module"     %% "jackson-module-scala"
  ).map(_ % jacksonVersion)



  val pekkoOverrides = Seq(
    "org.apache.pekko" %% "pekko-actor",
    "org.apache.pekko" %% "pekko-actor-typed",
    "org.apache.pekko" %% "pekko-stream",
    "org.apache.pekko" %% "pekko-slf4j",
    "org.apache.pekko" %% "pekko-serialization-jackson",
    "org.apache.pekko" %% "pekko-protobuf-v3",
    "org.apache.pekko" %% "pekko-testkit"
  ).map(_ % pekkoVersion)

  val all: Seq[ModuleID] = compile ++ jacksonOverrides ++ pekkoOverrides ++ test

}
