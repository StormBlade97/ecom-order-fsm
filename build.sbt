
name := """ros-state-management"""
organization := "connected-retail"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.1"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % "2.6.5"
libraryDependencies += "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.5" % Test
libraryDependencies += "com.typesafe.akka" %% "akka-protobuf-v3" % "2.6.5"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.5"
libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.3"
libraryDependencies += "com.typesafe.akka" %% "akka-persistence-typed" % "2.6.5"
libraryDependencies += "org.mockito" % "mockito-all" % "2.0.0-beta" % Test
libraryDependencies += "com.typesafe.play" %% "play-json-joda" % "2.8.1"
libraryDependencies += "com.typesafe.akka" %% "akka-stream-typed" % "2.6.5"



scalacOptions in Compile ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint")

// show full stack traces and test case durations
testOptions in Test += Tests.Argument("-oDF")