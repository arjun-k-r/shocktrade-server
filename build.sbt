import play.Play.autoImport._
import sbt.Keys._
import sbt._

val myScalaVersion = "2.11.6"
val myAkkaVersion = "2.3.9"
val myPlayVersion = "2.3.8" //2.4.0-M3"

scalacOptions ++= Seq("-deprecation", "-encoding", "UTF-8", "-feature", "-target:jvm-1.7", "-unchecked",
  "-Ywarn-adapted-args", "-Ywarn-value-discard", "-Xlint")

javacOptions ++= Seq("-Xlint:deprecation", "-Xlint:unchecked", "-source", "1.7", "-target", "1.7", "-g:vars")

lazy val appScalaJvm = (project in file("app-server"))
  .settings(
    name := "shocktrade-server",
    organization := "shocktrade.com",
    version := "0.8.0",
    scalaVersion := myScalaVersion,
    ivyScala := ivyScala.value map (_.copy(overrideScalaVersion = true)),
    libraryDependencies ++= Seq(cache, filters, ws,
      // Shocktrade/ldaniels528 dependencies
      //
      "com.ldaniels528" %% "commons-helpers" % "0.1.0",
      "com.ldaniels528" %% "play-json-compat" % "0.1.0",
      "com.ldaniels528" %% "shocktrade-services" % "0.4.4",
      "com.ldaniels528" %% "tabular" % "0.1.2",
      //
      // TypeSafe dependencies
      //
      "com.typesafe.akka" %% "akka-testkit" % myAkkaVersion % "test",
      "com.typesafe.play" %% "play-ws" % myPlayVersion,
      "com.typesafe.play" %% "twirl-api" % "1.0.4",
      //
      // Third Party dependencies
      //
      "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2.1",
      "org.reactivemongo" %% "play2-reactivemongo" % "0.10.5.0.akka23",
      //
      // Web Jar dependencies
      //
      "org.webjars" %% "webjars-play" % "2.3.0-3"
    ))
  .enablePlugins(play.PlayScala, play.twirl.sbt.SbtTwirl)

// loads the jvm project at sbt startup
onLoad in Global := (Command.process("project appScalaJvm", _: State)) compose (onLoad in Global).value
