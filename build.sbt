ThisBuild / scalaVersion := "3.3.6"

lazy val root = project
  .in(file("."))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "zio-dynamodb-codec-poc",
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio-blocks-schema" % "0.0.33",
      "software.amazon.awssdk"  % "dynamodb"          % "2.31.61",
      "org.systemfw"           %% "dynosaur-core"     % "0.7.0"
    ),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
