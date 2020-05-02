name := """ros-state-management"""
organization := "connected-retail"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.1"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
libraryDependencies += "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.5" % Test


// Adds additional packages into Twirl
//TwirlKeys.templateImports += "connected-retail.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "connected-retail.binders._"
