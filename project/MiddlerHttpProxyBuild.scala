import sbt._
import sbt.Keys._

object MiddlerHttpProxyBuild extends Build {

  val repos = Seq(
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
    "spray repo" at "http://repo.spray.io")

  lazy val middlerHttpProxy = Project(
    id = "middler-http-proxy",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "Middler HTTP Proxy",
      organization := "com.nevercertain.middler",
      version := "0.1-SNAPSHOT",
      scalaVersion := "2.10.2",
      resolvers ++= repos,
      libraryDependencies ++= Seq(
        "com.typesafe.akka" % "akka-actor_2.10" % "2.2.0-RC1",
        "io.spray" % "spray-can" % "1.2-M8")
    )
  )
}
