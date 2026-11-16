name := "product-service"
version := "1.0.0"
scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(
    libraryDependencies ++= Seq(
      guice,
      "com.mysql" % "mysql-connector-j" % "8.4.0",
      "com.auth0" % "java-jwt" % "4.4.0"
    )
  )
